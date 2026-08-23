# Stretch: one EC2 box + S3 (scoring rules) + ECR (images).
# This is intentionally not EKS — k8s is already covered locally with kind.
#
# Usage (from this folder, after `aws configure`):
#   terraform init
#   terraform plan
#   terraform apply
# Capture screenshots, then `terraform destroy` so it doesn't keep billing.

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_ecr_repository" "services" {
  for_each = toset([
    "fraudguard/gateway-service",
    "fraudguard/consumer-service",
    "fraudguard/scoring-service",
    "fraudguard/transaction-simulator",
  ])
  name                 = each.value
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}

resource "aws_s3_bucket" "artifacts" {
  bucket        = var.artifact_bucket
  force_destroy = true
}

resource "aws_s3_object" "scoring_rules" {
  bucket = aws_s3_bucket.artifacts.id
  key    = "scoring/scoring_rules.json"
  source = "${path.module}/../../services/scoring-service/rules/scoring_rules.json"
  etag   = filemd5("${path.module}/../../services/scoring-service/rules/scoring_rules.json")
}

resource "aws_security_group" "demo" {
  name        = "fraudguard-demo"
  description = "Demo SG — open to my IP only"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  ingress {
    description = "Gateway"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  ingress {
    description = "Grafana"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_instance" "demo" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  vpc_security_group_ids = [aws_security_group.demo.id]
  user_data              = <<-EOF
    #!/bin/bash
    dnf install -y docker
    systemctl enable --now docker
    usermod -aG docker ec2-user
    # Compose stack is copied/pulled separately; this just gets Docker onto the box.
  EOF

  tags = {
    Name = "fraudguard-demo"
  }
}
