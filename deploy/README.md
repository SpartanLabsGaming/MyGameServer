# Deploying MyGameServer

`master` is continuously deployed to a single Google Cloud VM.

```
push to master
      │
      ▼
GitHub Actions (.github/workflows/deploy.yml)
  · JDK 23, ./gradlew build installDist        ← compile + full test suite
  · rsync build/install/MyGameServer/  ──ssh──▶  deployer@<VM>:/opt/mygameserver/app/
  · ssh: sudo systemctl restart mygameserver
      │
      ▼
VM: systemd runs /opt/mygameserver/app/bin/MyGameServer  (user: mygameserver)
```

The VM only ever runs a JRE — the build happens in Actions, because an `e2-micro`
(1 GB RAM) cannot compile Kotlin reliably.

---

## One-time setup

### 1. Google Cloud setup

Run these locally (needs `gcloud`, authenticated: `gcloud auth login`) or in
[Cloud Shell](https://shell.cloud.google.com). Fill in your values:

```sh
PROJECT=<your-project-id>
ZONE=<your-vm-zone>          # e.g. us-central1-a  (must be us-west1/us-central1/us-east1 for the free tier)
REGION=${ZONE%-*}            # strips the trailing -a/-b/-c
VM=<your-vm-name>

gcloud config set project "$PROJECT"

# --- reserve a static external IP and attach it to the VM --------------------
# Free while attached to a running instance; ~$0.0025/hr when NOT attached.
gcloud compute addresses create mygameserver-ip --region "$REGION"

STATIC_IP=$(gcloud compute addresses describe mygameserver-ip --region "$REGION" --format='value(address)')
echo "Reserved: $STATIC_IP"

# Swap the VM's ephemeral IP for the reserved one:
gcloud compute instances delete-access-config "$VM" --zone "$ZONE" --access-config-name "External NAT"
gcloud compute instances add-access-config    "$VM" --zone "$ZONE" \
  --access-config-name "External NAT" --address "$STATIC_IP"

# --- open the game's UDP ports ---------------------------------------------
# 9998        = handshake listener (WebTools COMMON_LISTEN_PORT)
# 9990-9997   = the dedicated per-connection ports for up to maxConnections=4 clients
#               (each client n gets receive port 9996-2n; 9998 - 2*maxConnections .. 9997)
gcloud compute firewall-rules create mygameserver-udp \
  --network default --direction INGRESS --action ALLOW \
  --rules udp:9990-9998 --source-ranges 0.0.0.0/0 \
  --target-tags mygameserver

gcloud compute instances add-tags "$VM" --zone "$ZONE" --tags mygameserver
```

SSH (`tcp:22`) is already allowed by the network's default `default-allow-ssh` rule.

### 2. Provision the VM

SSH into the VM (`gcloud compute ssh "$VM" --zone "$ZONE"`), then:

```sh
curl -fsSL https://raw.githubusercontent.com/SpartanLabsGaming/MyGameServer/master/deploy/provision-vm.sh | sudo bash
```

This installs Temurin JRE 23, creates the `mygameserver` and `deployer` users,
installs `/etc/systemd/system/mygameserver.service`, drops in the CI deploy
public key, and grants `deployer` a one-command sudo rule to restart the
service. It is idempotent — re-run it after any change to
`deploy/provision-vm.sh` or the unit.

> Assumes a Debian- or Ubuntu-based image (the GCP default). Other images: adapt
> the JRE install in `provision-vm.sh`.

### 3. GitHub setup

The deploy key pair is already generated and its private half stored as the
`DEPLOY_SSH_KEY` repo secret; `DEPLOY_USER` is set to `deployer`. You need to:

```sh
# let this machine's gh token push workflow files
gh auth refresh -h github.com -s workflow

# point the pipeline at the VM
gh secret set DEPLOY_HOST --repo SpartanLabsGaming/MyGameServer --body <STATIC_IP>
```

| Secret | Value | Set by |
|---|---|---|
| `DEPLOY_SSH_KEY` | ed25519 private key (`github-actions-deploy@MyGameServer`) | ✅ already set |
| `DEPLOY_USER` | `deployer` | ✅ already set |
| `DEPLOY_HOST` | the reserved static IP | **you** |

### 4. First deploy

```sh
gh workflow run "Build and deploy" --repo SpartanLabsGaming/MyGameServer
```

or just push to `master`. Watch it: `gh run watch` locally, and
`journalctl -u mygameserver -f` on the VM.

---

## Operating it

| Task | Command (on the VM) |
|---|---|
| Live logs | `journalctl -u mygameserver -f` |
| Status | `systemctl status mygameserver` |
| Restart | `sudo systemctl restart mygameserver` |
| Stop / start | `sudo systemctl stop\|start mygameserver` |

**Rollback:** re-run the "Build and deploy" workflow from an older commit
(`gh workflow run "Build and deploy" --ref <sha>` — note it deploys the tree at
that ref), or `git revert` and push.

**Rotate the deploy key:** generate a new `ed25519` pair, replace
`CI_DEPLOY_PUBKEY` in `provision-vm.sh`, re-run the script on the VM, then
`gh secret set DEPLOY_SSH_KEY < newkey`.

---

## Caveats

- **Free-tier egress is 1 GB/month (North America).** The server broadcasts the
  full world state to every client at 60 Hz; under any real use this is
  exceeded quickly. Watch billing, or throttle the broadcast before opening it
  up.
- **UDP return path.** WebTools has the server send to a fixed client-side port;
  a client behind NAT without a port-forward may not receive datagrams. This is
  a library-level concern, unrelated to the deploy.
- **`e2-micro` is shared-core / burstable.** Fine for this single in-memory
  world; not for anything heavier.
- The VM's IP only stays put because it is *reserved* — if you skipped step 1,
  update `DEPLOY_HOST` after every VM stop/start.
