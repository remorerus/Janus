package com.ymid.wakeonlan.shutdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ShutdownModel {

    private final List<String> sshAddresses;
    private final int sshPort;
    private final String username;
    private final String password;
    private final String command;
    private final String sshAuthType;
    private final String sshKeyAlias;

    public ShutdownModel(String sshAddress, int sshPort, String username, String password, String command,
                         String sshAuthType, String sshKeyAlias) {
        this(Collections.singletonList(sshAddress), sshPort, username, password, command, sshAuthType, sshKeyAlias);
    }

    public ShutdownModel(List<String> sshAddresses, int sshPort, String username, String password, String command,
                         String sshAuthType, String sshKeyAlias) {
        this.sshAddresses = Collections.unmodifiableList(new ArrayList<>(sshAddresses == null
                ? Collections.emptyList() : sshAddresses));
        this.sshPort = sshPort;
        this.username = username;
        this.password = password;
        this.command = command;
        this.sshAuthType = "key";
        this.sshKeyAlias = sshKeyAlias;
    }

    public ShutdownModel(String sshAddress, int sshPort, String username, String password, String command) {
        this(sshAddress, sshPort, username, password, command, "password", null);
    }

    public String getSshAddress() { return sshAddresses.isEmpty() ? null : sshAddresses.get(0); }
    public List<String> getSshAddresses() { return sshAddresses; }
    public int getSshPort() { return sshPort; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getCommand() { return command; }
    public String getSshAuthType() { return sshAuthType; }
    public String getSshKeyAlias() { return sshKeyAlias; }

    public boolean isKeyAuth() {
        return true;
    }
}
