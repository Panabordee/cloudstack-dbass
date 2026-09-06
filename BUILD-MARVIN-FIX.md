# The recurring "marvin error" — cause and permanent fix (2026-09-06)

## What the failure looks like

```
[ERROR] Failed to execute goal org.codehaus.mojo:exec-maven-plugin:3.2.0:exec
        (generate-sources) on project cloud-marvin: Command execution failed.:
        Process exited with an error: 1 (Exit value: 1)
```

Run the same step by hand and it says what maven does not:

```
$ cd tools/marvin/marvin && python3 codegenerator.py -s ../../apidoc/target/commands.xml
the spec file ../../apidoc/target/commands.xml does not exists
```

## The chain

marvin does not fail. **It is the last link in a chain that starts three
modules earlier:**

1. some builds were run as **root** (`sudo mvn`, or from a root shell), which
   left the build output owned by root — 137,883 root-owned files in this
   working tree when it was checked today, including
   `target/maven-shared-archive-resources/`
2. the next build runs as `nacl` and the **root project** fails immediately:
   ```
   [ERROR] maven-remote-resources-plugin:1.3:process on project cloudstack:
           Error finding remote resources manifests:
           /home/nacl/dbaas-v2/target/maven-shared-archive-resources/META-INF/NOTICE
           (Permission denied)
   ```
3. because the reactor stopped there, `tools/apidoc` never runs, so
   `tools/apidoc/target/commands.xml` is never generated
4. `cloud-marvin`'s `generate-sources` runs `codegenerator.py -s
   ../../apidoc/target/commands.xml`, the file is missing, python exits 1, and
   maven reports it as a marvin failure

So the message names the wrong module, which is why it looked like a marvin
problem worth working around rather than an ownership problem worth fixing.

Note this is the second time root-owned files have cost this project time: the
same thing happened after a `git fetch` was run as root (recorded in the
project notes).

## The permanent fix

**1. Ownership is now correct** (done this session):

```bash
sudo chown -R nacl:nacl /home/nacl/dbaas-v2
find /home/nacl/dbaas-v2 -user root ! -path '*/.git/*' | wc -l   # → 0
```

`~/.m2` and `.git` were checked too and were already clean.

**2. Build as `nacl`, always.** Never `sudo mvn`, never build from a root
shell, never `sudo git` in this tree. The only steps that legitimately need
root are installing the resulting packages (`dpkg -i`), touching
`/usr/share/cloudstack-management/**`, and image work — none of which write
into the source tree.

**3. Pre-flight check, one line, before any build:**

```bash
find . -user root ! -path './.git/*' | head
```

Anything printed means the tree is poisoned again; `chown -R nacl:nacl .`
before doing anything else. Put this at the top of the build section of any
runbook that anyone (or any agent) follows.

**4. If you build marvin on its own, include `-am`.** `mvn -pl tools/marvin`
alone fails with the identical error for a different reason: apidoc is not in
the reactor, so the spec file it needs is never produced. `cloud-marvin`
already declares a dependency on `cloud-apidoc` (`tools/marvin/pom.xml:32-37`),
so `-am` is enough — no pom change is needed.

Correct invocations:

```bash
mvn -pl tools/apidoc,tools/marvin -am -Pdeveloper -DskipTests package   # just these
mvn clean package -Psystemvm,developer -Dsystemvm                       # what the .deb build runs
```

## What this does not change

`gen_toc.py`'s category map already covers the DBaaS commands: every command
name added so far contains `Dbaas` or `Database`, both of which are mapped
(`tools/apidoc/gen_toc.py:289-296`). A future command that contains neither
would raise `Need to add a category for <name>`, and that failure would again
surface as a marvin error — worth remembering, but it is not what is happening
today.
