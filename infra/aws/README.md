# AWS stretch (EC2 + S3 + ECR)

I did **not** stand up EKS. Kubernetes is already exercised locally (`k8s/` + kind).
This folder is just enough Terraform to prove I can push images, drop the scoring
rules JSON on S3, and run the Compose stack on a single EC2 box.

## What it creates

- ECR repos for the four service images
- An S3 bucket with `scoring/scoring_rules.json`
- One Amazon Linux 2023 EC2 (`t3.medium`) with Docker installed
- A security group opened for SSH / gateway / Grafana from `allowed_cidr`

## How I ran it (and then tore it down)

1. Change `artifact_bucket` in `variables.tf` to something globally unique.
2. Set `allowed_cidr` to `YOUR_IP/32`.
3. `terraform init && terraform apply`
4. Push images: `aws ecr get-login-password ... | docker login ...` then `docker tag` / `docker push`.
5. SSH in, install Docker Compose, copy `docker-compose.yml`, set `AWS_S3_BUCKET` on scoring-service.
6. Screenshot EC2, `docker ps`, S3, Grafana on the public IP.
7. **`terraform destroy`** — leaving a t3.medium up overnight is a waste.

Scoring-service already knows how to read rules from S3: set `AWS_S3_BUCKET` and
`AWS_S3_KEY=scoring/scoring_rules.json`. If S3 is unreachable it falls back to the
file baked into the image.
