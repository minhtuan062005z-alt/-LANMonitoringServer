package lanmonitoringserver.model;

public enum AnsibleConnectionType {
    SSH,
    PSRP,
    WINRM;

    public boolean supports(AnsiblePlatform platform) {
        return platform == AnsiblePlatform.WINDOWS || this == SSH;
    }

    public String ansibleValue() { return name().toLowerCase(java.util.Locale.ROOT); }
}
