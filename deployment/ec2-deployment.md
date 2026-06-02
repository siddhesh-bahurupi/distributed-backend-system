# Single EC2 Deployment With Docker Compose

This deployment runs the existing stack on one Ubuntu EC2 instance:

- three `gateway-service` containers
- one shared Redis container for distributed rate-limit state
- `product-service`, `order-service`, and PostgreSQL
- Prometheus and Grafana

It intentionally adds no load balancer, proxy, Kubernetes platform, or managed
container service. Each gateway replica is exposed through its Docker-assigned
host port on the EC2 instance.

## 1. Launch The EC2 Instance

Create one EC2 instance with:

- Ubuntu Server 24.04 LTS
- an SSH key pair downloaded as a `.pem` file
- a public IPv4 address, or an Elastic IP if the endpoint must remain stable
- enough CPU, memory, and disk for Java builds plus all running containers

For a small demonstration environment, start with a general-purpose instance
that has at least 4 GiB RAM and increase capacity if builds or dashboards show
resource pressure.

## 2. Configure The Security Group

Create inbound rules with restricted sources wherever possible:

| Purpose | Protocol | EC2 Port | Source Recommendation |
| --- | --- | --- | --- |
| SSH administration | TCP | `22` | Your public IP only |
| Grafana UI | TCP | `3000` | Your public IP or team VPN only |
| Prometheus UI, optional | TCP | `9090` | Your public IP or team VPN only |
| Gateway replica API access | TCP | Docker-assigned ports discovered after startup | Client/test IP addresses only |

Do **not** permit inbound internet access to PostgreSQL (`5432`), Redis
(`6379`), product-service (`8081`), order-service (`8082`), Tempo (`3200`,
`4317`, `4318`), or unrestricted gateway port ranges.

### Gateway Port Note

The existing scaled Compose configuration publishes each gateway container on
an available EC2 host port. After deployment, discover the assigned ports:

```bash
sudo docker compose ps gateway-service
sudo docker compose port gateway-service 8080 --index 1
sudo docker compose port gateway-service 8080 --index 2
sudo docker compose port gateway-service 8080 --index 3
```

For remote API testing, add a separate Custom TCP inbound rule for each
reported host port. If gateway containers are recreated and their host ports
change, update those inbound rules.

For administration-only observability, a safer alternative is to open only
SSH and use an SSH tunnel instead of exposing Grafana or Prometheus:

```bash
ssh -i ~/keys/backend-ec2.pem \
  -L 3000:localhost:3000 \
  -L 9090:localhost:9090 \
  ubuntu@<ec2-public-ip>
```

Then open Grafana at `http://localhost:3000` and Prometheus at
`http://localhost:9090` from your workstation.

## 3. SSH Into EC2

On macOS or Linux, limit private-key permissions once:

```bash
chmod 400 ~/keys/backend-ec2.pem
```

Connect to the Ubuntu instance:

```bash
ssh -i ~/keys/backend-ec2.pem ubuntu@<ec2-public-ip>
```

On Windows PowerShell, use the key file path:

```powershell
ssh -i C:\Keys\backend-ec2.pem ubuntu@<ec2-public-ip>
```

## 4. Install Git, Docker, And Docker Compose

Run these commands on the EC2 instance. They install Docker Engine and the
Docker Compose plugin from Docker's official Ubuntu package repository.

```bash
sudo apt update
sudo apt install -y ca-certificates curl git

sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
sudo docker --version
sudo docker compose version
```

The commands below continue using `sudo docker` so deployment does not depend
on Docker group permissions or reconnecting the SSH session.

## 5. Clone The Project

Choose an application directory and clone the repository:

```bash
mkdir -p ~/apps
cd ~/apps
git clone <repository-url> distributed-backend-system
cd distributed-backend-system
```

For a private repository, authenticate using the repository host's recommended
SSH deploy key or access-token workflow. Do not store credentials in shell
history or commit them into the project.

## 6. Deploy Three Gateway Replicas

From the project root on EC2, validate the Compose model and start the stack:

```bash
sudo docker compose config --quiet
sudo docker compose up --build --scale gateway-service=3 -d
sudo docker compose ps
```

Confirm that three gateway replicas are running and record their API ports:

```bash
sudo docker compose ps gateway-service
sudo docker compose port gateway-service 8080 --index 1
sudo docker compose port gateway-service 8080 --index 2
sudo docker compose port gateway-service 8080 --index 3
```

All gateway replicas connect to the same Compose `redis` service. Their
rate-limit state is therefore centralized in Redis rather than held in an
individual gateway process.

## 7. Access Grafana And Prometheus

If their security group ports are allowed from your IP:

```text
Grafana:    http://<ec2-public-ip>:3000
Prometheus: http://<ec2-public-ip>:9090
```

Grafana uses the credentials configured by the existing Compose stack. For any
Internet-reachable deployment, restrict port `3000` to trusted IP addresses
and replace demonstration credentials before treating it as a real
environment.

In Prometheus, open the targets page and confirm the `gateway-service` job has
three healthy targets. Prometheus discovers the scaled gateway containers
within the Docker network and scrapes each instance on container port `8080`.

## 8. Validate The Replicated Gateways

Run the following directly on EC2. It does not require exposing gateway ports
to the internet:

```bash
GW1=$(sudo docker compose port gateway-service 8080 --index 1 | sed 's/.*://')
GW2=$(sudo docker compose port gateway-service 8080 --index 2 | sed 's/.*://')
GW3=$(sudo docker compose port gateway-service 8080 --index 3 | sed 's/.*://')

for port in "$GW1" "$GW2" "$GW3" "$GW1" "$GW2" "$GW3"; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-Forwarded-For: 203.0.113.240" \
    "http://localhost:${port}/api/products"
done
```

Expected output for a fresh client IP is:

```text
200
200
200
200
200
429
```

Each replica handles only two of the six requests, but the sixth request is
rejected. This demonstrates that all three replicas consume one shared Redis
rate-limit budget. Use a new `X-Forwarded-For` address or wait for the
one-minute limiter window before repeating the check.

## 9. Optional k6 Validation From EC2

The included burst script performs the same cross-replica check with k6:

```bash
GW1=$(sudo docker compose port gateway-service 8080 --index 1 | sed 's/.*://')
GW2=$(sudo docker compose port gateway-service 8080 --index 2 | sed 's/.*://')
GW3=$(sudo docker compose port gateway-service 8080 --index 3 | sed 's/.*://')

cat load-tests/rate-limit-burst-test.js | sudo docker run --rm -i --network host \
  -e "GATEWAY_URLS=http://localhost:${GW1},http://localhost:${GW2},http://localhost:${GW3}" \
  -e CLIENT_IP=203.0.113.241 \
  -e VUS=1 -e REQUESTS=6 -e RATE=10 -e DURATION=10s \
  grafana/k6 run -
```

The k6 output should show one `429` response out of six requests and a passing
`rate_limited_responses` threshold.

## 10. Operational Commands

Check container state and recent logs:

```bash
sudo docker compose ps
sudo docker compose logs --tail=100 gateway-service
sudo docker compose logs --tail=100 prometheus grafana
```

Restart running services without rebuilding images:

```bash
sudo docker compose restart
```

After an EC2 reboot, return to the project directory and ensure the full stack
is running with three gateway replicas:

```bash
cd ~/apps/distributed-backend-system
sudo docker compose up --scale gateway-service=3 -d
```

Pull project changes, rebuild images, and retain three gateway replicas:

```bash
git pull
sudo docker compose up --build --scale gateway-service=3 -d
sudo docker compose ps gateway-service
```

Recreate only the gateway replicas after gateway changes:

```bash
sudo docker compose up --build --scale gateway-service=3 -d gateway-service
```

Stop the stack while retaining Docker volumes for PostgreSQL, Prometheus, and
Grafana data:

```bash
sudo docker compose down
```

Avoid `docker compose down -v` in normal operation because it removes named
volumes and deletes persisted database and monitoring data.
