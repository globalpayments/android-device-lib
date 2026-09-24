package com.globalpayments.library.terminals;

import com.globalpayments.library.terminals.enums.BaudRate;
import com.globalpayments.library.terminals.enums.ConnectionMode;
import com.globalpayments.library.terminals.enums.DataBits;
import com.globalpayments.library.terminals.enums.Environment;
import com.globalpayments.library.terminals.enums.Parity;
import com.globalpayments.library.terminals.enums.StopBits;
import com.tsys.payments.library.gateway.enums.GatewayType;

public class ConnectionConfig {
    private ConnectionMode connectionMode;
    private String ipAddress;
    private String port;
    private BaudRate baudRate;
    private Parity parity;
    private StopBits stopBits;
    private DataBits dataBits;
    private long timeout;
    private Credentials credentials;

    // Surcharge
    private boolean surchargeEnabled;
    @Deprecated
    private boolean surchargePreTax;
    @Deprecated
    private float surchargePercent = 3.0f;

    // saf options
    private boolean safEnabled;
    private int safExpirationInDays;

    // moby-specific
    private boolean mobyAutoRebootDisabled = false;

    private Environment environment;
    private GatewayType gateway;

    public ConnectionConfig() {
        timeout = 60000L;
        environment = Environment.TEST;
        gateway = GatewayType.PORTICO;
    }

    public ConnectionMode getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(ConnectionMode connectionMode) {
        this.connectionMode = connectionMode;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public BaudRate getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(BaudRate baudRate) {
        this.baudRate = baudRate;
    }

    public Parity getParity() {
        return parity;
    }

    public void setParity(Parity parity) {
        this.parity = parity;
    }

    public StopBits getStopBits() {
        return stopBits;
    }

    public void setStopBits(StopBits stopBits) {
        this.stopBits = stopBits;
    }

    public DataBits getDataBits() {
        return dataBits;
    }

    public void setDataBits(DataBits dataBits) {
        this.dataBits = dataBits;
    }

    public long getTimeout() {
        return timeout;
    }

    @Deprecated
    public void setTimeout(String timeout) {
        this.timeout = Long.getLong(timeout);
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public Environment getEnvironment() { return environment; }

    public void setEnvironment(Environment environment) { this.environment = environment; }

    public GatewayType getGateway() {
        return gateway;
    }

    public void setGateway(GatewayType gateway) {
        this.gateway = gateway;
    }

    public boolean isSafEnabled() {
        return safEnabled;
    }

    public void setSafEnabled(boolean safEnabled) {
        this.safEnabled = safEnabled;
    }

    public int getSafExpirationInDays() {
        return safExpirationInDays;
    }

    public void setSafExpirationInDays(int safExpirationInDays) {
        this.safExpirationInDays = safExpirationInDays;
    }

    public boolean isSurchargeEnabled() {
        return surchargeEnabled;
    }

    public void setSurchargeEnabled(boolean surchargeEnabled) {
        this.surchargeEnabled = surchargeEnabled;
    }

    @Deprecated
    public boolean isSurchargePreTax() {
        return surchargePreTax;
    }

    @Deprecated
    public void setSurchargePreTax(boolean surchargePreTax) {
        this.surchargePreTax = surchargePreTax;
    }

    @Deprecated
    public float getSurchargePercent() {
        return surchargePercent;
    }

    @Deprecated
    public void setSurchargePercent(float surchargePercent) {
        this.surchargePercent = surchargePercent;
    }

    public boolean isMobyAutoRebootDisabled() {
        return mobyAutoRebootDisabled;
    }

    public void setMobyAutoRebootDisabled(boolean mobyAutoRebootDisabled) {
        this.mobyAutoRebootDisabled = mobyAutoRebootDisabled;
    }
}
