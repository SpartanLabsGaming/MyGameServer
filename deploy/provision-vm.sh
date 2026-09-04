#!/usr/bin/env bash
#
# One-time (idempotent) provisioning for the Google Cloud VM that hosts MyGameServer.
#
#   Run ON THE VM, as root:
#     curl -fsSL https://raw.githubusercontent.com/SpartanLabsGaming/MyGameServer/master/deploy/provision-vm.sh | sudo bash
#   ...or copy this file over and `sudo bash provision-vm.sh`.
#
# It is safe to re-run: every step checks its own state first. Re-run it after
# changing the systemd unit or the CI deploy key below.
#
# What it sets up:
#   - a JRE 23 (Adoptium Temurin) — GameTools targets JVM 23
#   - user `mygameserver`  : unprivileged, runs the service, no login
#   - user `deployer`      : the identity GitHub Actions rsyncs in as
#   - /opt/mygameserver/app: the synced distribution (deployer writes, mygameserver reads)
#   - the mygameserver.service systemd unit, enabled
#   - a narrow sudoers rule letting `deployer` restart only this one service
#
# It does NOT open the firewall — that is a GCP-side change; see deploy/README.md.

set -euo pipefail

# --- the GitHub Actions deploy public key -------------------------------------
# Private half lives in the repo secret DEPLOY_SSH_KEY. To rotate: generate a new
# ed25519 pair, replace this line, re-run this script, update the secret.
CI_DEPLOY_PUBKEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKJE7AtTaUukbR1LqNclInyqtNOo7hW063hzFqGCs6RY github-actions-deploy@MyGameServer'
# -----------------------------------------------------------------------------

APP_DIR=/opt/mygameserver
SERVICE_USER=mygameserver
DEPLOY_USER=deployer

if [[ $EUID -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

echo "==> Installing rsync (the CI deploy transport)"
# GitHub Actions rsyncs the build onto the box; the minimal GCE Debian image
# ships without it, which fails the deploy with "rsync: command not found".
if ! command -v rsync &>/dev/null; then
  apt-get update -qq
  apt-get install -y -qq rsync
else
  echo "    rsync already present: $(rsync --version 2>&1 | head -n1)"
fi

echo "==> Installing Java 23 (Adoptium Temurin)"
if ! java -version 2>&1 | grep -q '"23'; then
  apt-get update -qq
  apt-get install -y -qq wget apt-transport-https gnupg
  mkdir -p /etc/apt/keyrings
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor > /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  # Adoptium's apt repo ships JDK packages only (no -jre variant); the JDK is a
  # superset and only ~130 MB more, which is nothing on the 30 GB free-tier disk.
  apt-get install -y -qq temurin-23-jdk
else
  echo "    Java 23 already present: $(java -version 2>&1 | head -n1)"
fi

echo "==> Creating service user '$SERVICE_USER'"
if ! id "$SERVICE_USER" &>/dev/null; then
  useradd --system --no-create-home --shell /usr/sbin/nologin --home-dir "$APP_DIR" "$SERVICE_USER"
fi

echo "==> Creating deploy user '$DEPLOY_USER'"
if ! id "$DEPLOY_USER" &>/dev/null; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi
# deployer must be able to write files that the service user can read
usermod -aG "$SERVICE_USER" "$DEPLOY_USER" || true

echo "==> Installing the CI deploy key for '$DEPLOY_USER'"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
touch "/home/$DEPLOY_USER/.ssh/authorized_keys"
if ! grep -qF "$CI_DEPLOY_PUBKEY" "/home/$DEPLOY_USER/.ssh/authorized_keys"; then
  echo "$CI_DEPLOY_PUBKEY" >> "/home/$DEPLOY_USER/.ssh/authorized_keys"
fi
chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"

echo "==> Creating $APP_DIR/app"
install -d -m 750 -o "$DEPLOY_USER" -g "$SERVICE_USER" "$APP_DIR"
install -d -m 750 -o "$DEPLOY_USER" -g "$SERVICE_USER" "$APP_DIR/app"

echo "==> Installing the systemd unit"
cat > /etc/systemd/system/mygameserver.service <<'UNIT'
[Unit]
Description=MyGameServer (authoritative game server)
Documentation=https://github.com/SpartanLabsGaming/MyGameServer
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=mygameserver
Group=mygameserver
WorkingDirectory=/opt/mygameserver
ExecStart=/opt/mygameserver/app/bin/MyGameServer
Environment=JAVA_OPTS=-Xmx192m -XX:+UseSerialGC
Restart=always
RestartSec=3
KillSignal=SIGTERM
TimeoutStopSec=15
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6
RestrictNamespaces=true
LockPersonality=true
StandardOutput=journal
StandardError=journal
SyslogIdentifier=mygameserver

[Install]
WantedBy=multi-user.target
UNIT

echo "==> Granting '$DEPLOY_USER' permission to restart only mygameserver"
cat > /etc/sudoers.d/deployer-mygameserver <<SUDO
$DEPLOY_USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart mygameserver, /usr/bin/systemctl start mygameserver, /usr/bin/systemctl stop mygameserver, /usr/bin/systemctl status mygameserver
SUDO
chmod 440 /etc/sudoers.d/deployer-mygameserver
visudo -cf /etc/sudoers.d/deployer-mygameserver

echo "==> Enabling the service"
systemctl daemon-reload
systemctl enable mygameserver

if [[ -x "$APP_DIR/app/bin/MyGameServer" ]]; then
  echo "==> App is present — (re)starting"
  systemctl restart mygameserver
  sleep 3
  systemctl is-active --quiet mygameserver && echo "    running" || systemctl status mygameserver --no-pager -l | tail -n 20
else
  echo "==> No app deployed yet. It will start on the first GitHub Actions deploy."
fi

cat <<DONE

Provisioning complete.

Next:
  1. Reserve/confirm this VM's static external IP and open the UDP firewall
     (deploy/README.md, "Google Cloud setup").
  2. Set the repo secret DEPLOY_HOST to that IP:
       gh secret set DEPLOY_HOST --repo SpartanLabsGaming/MyGameServer --body <IP>
  3. Push to master (or run the "Build and deploy" workflow) to ship the first build.

Watch it:   journalctl -u mygameserver -f
DONE
