package com.gemalto.chaos.ssh;

import com.gemalto.chaos.ssh.enums.ShellCapabilityType;
import com.gemalto.chaos.ssh.enums.ShellCommand;
import com.gemalto.chaos.ssh.enums.ShellType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;

public class ShellSessionCapabilityProvider {
    private static final Logger log = LoggerFactory.getLogger(ShellSessionCapabilityProvider.class);
    private SshManager sshManager;
    private ArrayList<ShellSessionCapability> capabilities = new ArrayList<>();

    public ShellSessionCapabilityProvider (SshManager sshManager) {
        this.sshManager = sshManager;
    }

    public ArrayList<ShellSessionCapability> getCapabilities () {
        return capabilities;
    }

    public void build () {
        log.debug("Collecting shell session capabilities");
        capabilities.add(getShellType());
    }

    private ShellSessionCapability getShellType () {
        ShellSessionCapability capability;
        SshCommandResult result = sshManager.executeCommand(ShellCommand.SHELLTYPE.toString());
        if (result.getExitStatus() != -1 && result.getCommandOutput().length() > 0) {
            String shellName = result.getCommandOutput();
            shellName = shellName.toUpperCase().trim();
            shellName = parseFileNameFromFilePath(shellName);
            for (ShellType type : ShellType.values()) {
                if (type.toString().matches(shellName)) {
                    capability = new ShellSessionCapability(ShellCapabilityType.SHELL);
                    capability.addCapabilityOption(type.name());
                    return capability;
                }
            }
        }
        capability = new ShellSessionCapability(ShellCapabilityType.SHELL);
        capability.addCapabilityOption(ShellType.UNKNOWN.name());
        return capability;
    }

    private String parseFileNameFromFilePath (String filepath) {
        return new File(filepath).getAbsoluteFile().getName();
    }
}