package com.suanla.relayq.core.scheduler;

import com.suanla.relayq.core.config.RelayqProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;

public final class WorkerIdentity {

    private static final int RANDOM_SUFFIX_LENGTH = 8;
    private static final int MAX_OWNER_LENGTH = 128;

    private final String instanceId;

    public WorkerIdentity(RelayqProperties properties) {
        this(resolve(Objects.requireNonNull(properties, "properties must not be null")));
    }

    public WorkerIdentity(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (instanceId.length() > MAX_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    "instanceId exceeds " + MAX_OWNER_LENGTH + " characters");
        }
        this.instanceId = instanceId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getOwner() {
        return instanceId;
    }

    public String value() {
        return instanceId;
    }

    @Override
    public String toString() {
        return instanceId;
    }

    private static String resolve(RelayqProperties properties) {
        String configured = properties.getInstanceId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, RANDOM_SUFFIX_LENGTH);
        String pid = Long.toString(ProcessHandle.current().pid());
        String fixedSuffix = "-" + pid + "-" + suffix;
        String hostname = hostname();
        int hostnameLimit = MAX_OWNER_LENGTH - fixedSuffix.length();
        if (hostname.length() > hostnameLimit) {
            hostname = hostname.substring(0, hostnameLimit);
        }
        return hostname + fixedSuffix;
    }

    private static String hostname() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname == null || hostname.isBlank() ? "unknown-host" : hostname;
        } catch (UnknownHostException | SecurityException error) {
            return "unknown-host";
        }
    }
}
