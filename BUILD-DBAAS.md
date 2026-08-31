# Building this branch (DBaaS)

This branch is `nacl/4.22` with the DBaaS UI work merged on top. The DBaaS API
plugin itself lives in a separate repository,
[dbaas-extension-forcloudstack](https://github.com/Panabordee/dbaas-extension-forcloudstack),
and is built separately — nothing here depends on it.

Kept in its own file rather than in `README.md` so it does not collide with
upstream every time this branch is rebased.

## Build gotchas

### Never pass `-Dsimulator` for a build you intend to install

`.github/workflows/ci.yml` uses it, which makes it easy to copy by mistake, but
that job exists to run integration tests. The flag activates a profile in
`plugins/pom.xml` **and** one in `client/pom.xml`, and the second one adds
`cloud-plugin-hypervisor-simulator` as a dependency of the client — so it lands
inside the uber jar that becomes
`/usr/share/cloudstack-management/lib/cloudstack-*.jar`.

A management server built that way loads `SimulatorGuru`, `SimulatorFencer`,
`SimulatorInvestigator`, `SimulatorHAProvider`, `SimulatorDiscoverer` and eight
mock DAOs at startup, and offers `Simulator` as a hypervisor type an operator
can pick by accident. It does not break KVM; it just has no business being
there.

To check a jar you already have:

```bash
unzip -l client/target/cloud-client-ui-*.jar \
  | grep -c "SimulatorGuru\|SimulatorDiscoverer\|MockVMDaoImpl"   # want 0
```

`plugins/storage/object/simulator` is unrelated — it is a default module and is
always present.

### KVM needs no profile

`hypervisors/kvm` is in the default module list, along with baremetal,
external, hyperv, ucs and xenserver. There is no `-P kvm`. Only two hypervisors
are gated: `vmware` behind `-Dnoredist`, `simulator` behind `-Dsimulator`.

Passing `-Dnoredist` on a host without the VMware SDK in the local repository
fails the build outright:

```
Could not find artifact com.cloud.com.vmware:vmware-vim25:jar:8.0
```

### `-DskipTests=true` is for build time, not because tests fail

The `.deb` build already takes around 37 minutes on this host; the test suite
is skipped to keep that from growing. It is **not** a workaround for broken
tests. Measured on cloudstackcve (JDK 17.0.20, Maven 3.8.7), every test that
had been suspected of failing passes:

| Test class | Result |
| --- | --- |
| `FileUtilTest`, `HttpUtilsTest`, `NetUtilsTest` | 109 tests, 0 failures |
| `BasicRestClientTest`, `RESTServiceConnectorTest` | 12 tests, 0 failures |

```bash
mvn -B -pl utils test -Dtest='FileUtilTest,HttpUtilsTest,NetUtilsTest'
mvn -B -pl utils test -Dtest='BasicRestClientTest,RESTServiceConnectorTest'
```

Both runs print Mockito stack traces and `ERROR`-level lines from `HttpUtils`
in passing tests, which is what the "everything is failing" impression came
from. Read the `Tests run:` summary, not the log noise.

A full run across all 148 modules has not been done here, so this says nothing
about modules other than `utils`.

## Commands

```bash
# jars only
export MAVEN_OPTS="-Xmx3g"
mvn -B -P developer,systemvm clean install -DskipTests=true -T2      # ~17 min

# .deb packages (calls debian/rules, which is the command releases use)
export ACS_BUILD_OPTS="-DskipTests=true"
./packaging/build-deb.sh --output-directory /home/nacl/dbaas-deb     # ~37 min
```

`build-deb.sh` rewrites `debian/changelog`, builds, then restores it. If it is
interrupted you are left with a modified `debian/changelog` and a
`debian/changelog.<timestamp>` beside it; restore with `git checkout --` and
delete the backup, or the next run appends a second `~noble` to every package
name.
