variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

variable "artifact_bucket" {
  type        = string
  description = "Globally unique S3 bucket for scoring rules"
  default     = "fraudguard-artifacts-CHANGE-ME"
}

variable "instance_type" {
  type    = string
  default = "t3.medium"
}

variable "allowed_cidr" {
  type        = string
  description = "Your public IP as x.x.x.x/32 — do not leave 0.0.0.0/0"
  default     = "0.0.0.0/0"
}
