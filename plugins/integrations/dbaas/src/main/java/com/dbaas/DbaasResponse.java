package com.dbaas;

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;

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

    @SerializedName("vmusername")
    @Param(description = "login user on the instance, when VM access was set up")
    private String vmUsername;

    @SerializedName("vmpassword")
    @Param(description = "login password on the instance, when VM access was set up")
    private String vmPassword;

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
    public void setVmUsername(String vmUsername) { this.vmUsername = vmUsername; }
    public void setVmPassword(String vmPassword) { this.vmPassword = vmPassword; }
    public String getVmUsername() { return vmUsername; }
    public String getVmPassword() { return vmPassword; }
    public void setFound(Boolean found) { this.found = found; }
}
