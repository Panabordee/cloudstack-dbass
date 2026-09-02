"""
Minimal signed CloudStack API client — just enough to look up a VM's IP.
No third-party deps beyond `requests`.
"""
import hashlib
import hmac
import base64
import urllib.parse
import requests


class CloudStackAPI:
    def __init__(self, api_url, api_key, secret_key, timeout=15):
        self.api_url = api_url
        self.api_key = api_key
        self.secret_key = secret_key
        self.timeout = timeout

    def _sign(self, params):
        # CloudStack expects: lowercase keys sorted, urlencoded, joined by '&',
        # HMAC-SHA1 with the secret key, base64, then urlencoded again.
        sorted_params = sorted((k.lower(), v) for k, v in params.items())
        query_string = "&".join(
            f"{urllib.parse.quote_plus(str(k))}={urllib.parse.quote_plus(str(v))}"
            for k, v in sorted_params
        )
        digest = hmac.new(
            self.secret_key.encode("utf-8"),
            query_string.lower().encode("utf-8"),
            hashlib.sha1,
        ).digest()
        signature = urllib.parse.quote_plus(base64.b64encode(digest).decode("utf-8"))
        return query_string, signature

    def call(self, command, **params):
        params = dict(params)
        params.update({
            "command": command,
            "apikey": self.api_key,
            "response": "json",
        })
        query_string, signature = self._sign(params)
        url = f"{self.api_url}?{query_string}&signature={signature}"
        resp = requests.get(url, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def get_vm_primary_ip(self, virtualmachine_id):
        """Returns the private/primary NIC IP for a VM. Adjust if you use
        multiple NICs / VPC tiers and need a specific one."""
        data = self.call("listVirtualMachines", id=virtualmachine_id)
        vms = data.get("listvirtualmachinesresponse", {}).get("virtualmachine", [])
        if not vms:
            raise RuntimeError(f"VM {virtualmachine_id} not found via listVirtualMachines")
        nics = vms[0].get("nic", [])
        if not nics:
            raise RuntimeError(f"VM {virtualmachine_id} has no NICs")
        # TODO: if the VM has multiple NICs (VPC tiers), pick the right one
        # explicitly instead of nics[0] — e.g. match by network name/tag.
        return nics[0]["ipaddress"]
