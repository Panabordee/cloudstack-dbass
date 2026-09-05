package com.dbaas;

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;

// The instance's own login password is deliberately not part of this
// response: v1 minted and returned one itself (vmusername/vmpassword,
// shown once, unrecoverable), which this plugin no longer does. A
// password-enabled template gets one from CloudStack's own deploy response,
// and resetPasswordForVirtualMachine resets it -- see PLAN.md.
public class DbaasResponse extends BaseResponse {

    @SerializedName("engine")
    @Param(description = "the template/engine used (dbaas-mysql, dbaas-postgresql, dbaas-mongodb)")
    private String engine;

    @SerializedName("host")
    @Param(description = "database host / IP address")
    private String host;

    @SerializedName("port")
    @Param(description = "database port")
    private Integer port;

    @SerializedName("database")
    @Param(description = "database name")
    private String database;

    @SerializedName("username")
    @Param(description = "database username")
    private String username;

    @SerializedName("password")
    @Param(description = "database password")
    private String password;

    @SerializedName("status")
    @Param(description = "provisioning status of the stored credential: 'pending' while the instance has"
            + " not confirmed it configured its engine, 'confirmed' once it has, 'failed' when it"
            + " reported it could not", since = "4.23.0.0")
    private String status;

    @SerializedName("statusmessage")
    @Param(description = "why provisioning failed, when the status is 'failed'", since = "4.23.0.0")
    private String statusMessage;

    @SerializedName("found")
    @Param(description = "true when a stored database credential exists for the instance", since = "4.22.2.0")
    private Boolean found;

    public void setEngine(String engine) { this.engine = engine; }
    public void setHost(String host) { this.host = host; }
    public void setPort(Integer port) { this.port = port; }
    public void setDatabase(String database) { this.database = database; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }

    public String getEngine() { return engine; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public void setFound(Boolean found) { this.found = found; }
    public void setStatus(String status) { this.status = status; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}
