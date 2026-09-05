# VM login password — diagnosis (2026-09-05)

Two symptoms, one cause:

- the tenant cannot choose the instance's own login password
- the tenant never receives a generated one either, so a DBaaS instance is
  reachable only by SSH key or console

## What PLAN.md says, and why it is wrong

PLAN.md §2 records the decision as "the tenant cannot choose their own VM
password (no such API)". **That is not true on 4.23.**

`BaseDeployVMCmd.java:89` declares:

```java
@Parameter(name = ApiConstants.PASSWORD, type = CommandType.STRING,
    description = "The password of the virtual machine. If null, a random password will be generated for the VM.")
```

and `ResetVMPasswordCmd.java:58` takes the same parameter (`since = "4.19.0"`).
So both a chosen password at deploy and a chosen password on reset are
supported by the platform. The design note needs correcting.

## Why no password reaches the instance today

The password only ever reaches a config-drive guest through
`vendor_data.json`, and `ConfigDriveBuilder` writes it there **only when the
VM's metadata contains a password entry**:

- `ConfigDriveBuilder.java:133-147` — scans `vmData` for `PASSWORD_FILE`; if
  absent it writes `vendor_data.json` as `{}`
- `NetworkModelImpl.java:2975` — the password entry is added to `vmData`
- `ConfigDriveNetworkElement.java:421` — the value it uses comes from
  `vm.getParameter(VirtualMachineProfile.Param.VmPassword)`

That parameter is set on the API deploy path
(`UserVmManagerImpl.java:5555-5557`, inside
`startVirtualMachine(DeployVMCmd)`), and the password is generated or read on
that path by `getCurrentVmPasswordOrDefineNewPassword`
(`UserVmManagerImpl.java:6186-6207`).

The DBaaS flow misses both:

1. the wizard deploys with `startvm=false` (`CreateDatabaseInstance.vue:428`),
   so `startVirtualMachine(DeployVMCmd)` never runs — no password is generated
   and the deploy response has none to show the tenant
2. the plugin starts the instance itself with
   `userVmService.startVirtualMachine(userVm, null)`
   (`DbaasManagerImpl.java:521`), the internal two-argument path, which
   carries no `Param.VmPassword` at all

Confirmed on the live host: the config drive of `dbaas-debug` (i-2-67-VM)
contains `openstack/latest/vendor_data.json` = `{}`, on a template registered
with `passwordenabled=true`.

## What is *not* the fix

Adding a `Password` row to the network's service map. `Network.Service` in
4.23 has no such value (`Network.java:103-119`), and the previous attempt NPEd
`listNetworks`. The password does not travel as a network service on the
config-drive path — it rides in `vendor_data.json`, which is why nothing in
the service map needs to change.

## Recommended fix

Everything needed already exists as public API, and it fits the current flow
because the instance is **already Stopped** at the point `createDatabase` runs:

1. wizard collects an optional VM password (validated like any other), deploys
   with `startvm=false` exactly as today
2. before attaching the user data, call `resetPasswordForVirtualMachine`
   with the tenant's value, or with none to have CloudStack generate one.
   `ConfigDriveNetworkElement.savePassword` requires the instance to be
   stopped (`:239-250`) — which it is — and stores it encrypted in the VM
   details (`storePasswordInVmDetails`, `:297-302`)
3. the plugin starts the instance as it does now; the config drive is built at
   start, `Param.VmPassword` is populated from the stored detail, and
   `vendor_data.json` carries the cloud-config that sets it
4. the response returns the password once, and the wizard shows it in the same
   dialog that already shows the database credential

Rejected alternative: writing `chpasswd` into the cloud-config the plugin
already generates. It works and needs no CloudStack API at all, but it puts a
second copy of a password into user data that CloudStack stores in cleartext,
and it bypasses `resetPasswordForVirtualMachine`, so the platform's own reset
action would then silently disagree with reality.

## Acceptance for the fix

- deploy with a chosen password → log in on the console with exactly that
  password
- deploy with none → a password is returned once and works
- `vendor_data.json` on the config drive is no longer `{}`
- `resetPasswordForVirtualMachine` afterwards still works and the new password
  applies on the next start
- none of it depends on the virtual router
