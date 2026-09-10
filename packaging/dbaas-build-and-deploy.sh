#!/bin/bash
# Build the CloudStack packages this branch needs and install them on this host.
#
# This is the ordinary CloudStack packaging build -- packaging/build-deb.sh,
# the same script upstream uses -- with two things wired in that otherwise have
# to be remembered by hand every time, and were the cause of two failed builds
# on 2026-09-10:
#
#   1. ACS_BUILD_OPTS="-DskipTests"
#      debian/rules runs `mvn clean package` with no -DskipTests, so packaging
#      runs the whole unit-test suite. On this host that fails in cloud-utils
#      -- a module DBaaS does not touch -- with
#          Could not initialize plugin: interface org.mockito.plugins.MockMaker
#      across TestProfiler, SSLUtilsTest, FileUtilTest, HttpUtilsTest and
#      BasicRestClientTest. It is a Mockito 5.16.1 / JDK 17 dynamic-agent
#      incompatibility in CloudStack's own test harness (the root pom already
#      passes -Djdk.attach.allowAttachSelf=true and it is still not enough),
#      not a regression from anything in this branch. debian/rules exposes
#      ACS_BUILD_OPTS for exactly this, so this is the supported mechanism
#      rather than a patch. To chase the tests themselves later, run
#      `mvn -pl utils -am test` on its own -- it does not block packaging.
#
#   2. NODE_OPTIONS=--openssl-legacy-provider
#      the UI is webpack 4, which calls crypto.createHash('md4'); OpenSSL 3
#      (Node 22 on this host) removed it, so the UI build dies with
#      ERR_OSSL_EVP_UNSUPPORTED without this.
#
# Usage:
#   packaging/dbaas-build-and-deploy.sh              # build only
#   packaging/dbaas-build-and-deploy.sh --deploy     # build, then install + restart
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${DBAAS_DEB_OUT:-/home/nacl/dbaas-v2-deb}"
DEPLOY=0
[[ "${1:-}" == "--deploy" ]] && DEPLOY=1

log() { printf '\n=== %s ===\n' "$*"; }

log "pre-flight"
free_gb=$(df --output=avail -BG / | tail -1 | tr -dc '0-9')
echo "free space on /: ${free_gb}G"
if (( free_gb < 5 )); then
    echo "REFUSING: a full package build needs headroom and / has ${free_gb}G." >&2
    echo "Reclaim space first (target/ dirs, ~/.m2/repository/org/apache/cloudstack" >&2
    echo "-- both are rebuilt by this script -- or old logs) and re-run." >&2
    exit 1
fi
java -version 2>&1 | head -1
mvn -v 2>&1 | head -1

log "maven packages (this is the long one: expect 30-60 min)"
cd "$REPO"
mkdir -p "$OUT"

# build-deb.sh appends a version stanza to debian/changelog on every run and
# leaves a timestamped copy behind, so running it twice produces
# 4.23.0.0~noble~noble and a third run ~noble~noble~noble -- the package
# version drifts further from the real one each time. Snapshot the file and
# put it back afterwards (including on failure), so a re-run always starts
# from the same place.
CHANGELOG_SNAPSHOT=$(mktemp)
cp debian/changelog "$CHANGELOG_SNAPSHOT"
restore_changelog() {
    cp "$CHANGELOG_SNAPSHOT" "$REPO/debian/changelog"
    rm -f "$CHANGELOG_SNAPSHOT"
    # the copies build-deb.sh makes are pure noise once the changelog is back
    find "$REPO/debian" -maxdepth 1 -name "changelog.$(date +%Y%m%d)T*" -delete 2>/dev/null || true
}
trap restore_changelog EXIT

ACS_BUILD_OPTS="-DskipTests" MAVEN_OPTS="${MAVEN_OPTS:--Xmx3g}" \
    packaging/build-deb.sh -o "$OUT"

log "built packages"
ls -la "$OUT"/*.deb
mgmt=$(ls "$OUT"/cloudstack-management_*.deb 2>/dev/null | head -1 || true)
ui=$(ls "$OUT"/cloudstack-ui_*.deb 2>/dev/null | head -1 || true)
if [[ -z "$mgmt" ]]; then
    echo "REFUSING: no cloudstack-management .deb was produced." >&2
    exit 1
fi

if (( DEPLOY == 0 )); then
    log "build only -- not deploying"
    echo "To install what was just built:"
    echo "  packaging/dbaas-build-and-deploy.sh --deploy"
    exit 0
fi

# ---- deploy ----------------------------------------------------------------
# Installing over a running management server is a real change to a working
# zone: back up what is being replaced, and say what to run to undo it.
STAMP=$(date +%Y%m%d%H%M%S)
BACKUP="/home/nacl/dbaas-deploy-backup-${STAMP}"
log "deploying (backup: ${BACKUP})"
mkdir -p "$BACKUP"
sudo cp -a /usr/share/cloudstack-management/lib/cloudstack-*.jar "$BACKUP/" 2>/dev/null || true
sudo tar -C /usr/share/cloudstack-management -czf "$BACKUP/webapp.tgz" webapp 2>/dev/null || true
dpkg -l | grep -E "^ii\s+cloudstack-(management|ui)" > "$BACKUP/package-versions.txt" 2>/dev/null || true
echo "undo:  sudo dpkg -i \$(cat ${BACKUP}/package-versions.txt | awk '{print \$2\"=\"\$3}')"
echo "       or restore ${BACKUP}/ by hand and: sudo systemctl restart cloudstack-management"

sudo systemctl stop cloudstack-management
sudo dpkg -i "$mgmt"
[[ -n "$ui" ]] && sudo dpkg -i "$ui"

# The DBaaS extension config is not owned by the package; a reinstall would
# otherwise leave the engines map missing and every createDatabase would fail
# with "template ... is not listed in the engines map".
log "restoring the DBaaS extension config"
sudo mkdir -p /usr/share/cloudstack-management/extensions/dbaas
sudo cp "$REPO/extensions/dbaas/config.example.json" \
        /usr/share/cloudstack-management/extensions/dbaas/config.json.packaged-default
if [[ -f "$BACKUP/config.json" ]]; then
    sudo cp "$BACKUP/config.json" /usr/share/cloudstack-management/extensions/dbaas/config.json
fi

sudo systemctl start cloudstack-management

log "waiting for the management server"
for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' \
        "http://localhost:8080/client/api?command=listCapabilities&response=json" || true)
    [[ "$code" == "401" || "$code" == "200" ]] && { echo "up (HTTP $code)"; break; }
    sleep 5
done

log "post-deploy checks"
systemctl is-active cloudstack-management
curl -s -o /dev/null -w "client/api -> %{http_code}\n" \
    "http://localhost:8080/client/api?command=listCapabilities&response=json"
curl -s -o /dev/null -w "client/     -> %{http_code}\n" "http://localhost:8080/client/"
echo "DBaaS commands registered:"
sudo grep -c "dbaas" /usr/share/cloudstack-management/webapp/WEB-INF/classes/commands.properties 2>/dev/null || true
echo
echo "Backup of what was replaced: ${BACKUP}"
