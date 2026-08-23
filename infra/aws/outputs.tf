output "ec2_public_ip" {
  value = aws_instance.demo.public_ip
}

output "s3_bucket" {
  value = aws_s3_bucket.artifacts.bucket
}

output "ecr_urls" {
  value = { for k, r in aws_ecr_repository.services : k => r.repository_url }
}
