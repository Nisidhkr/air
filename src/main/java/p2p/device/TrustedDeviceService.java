package p2p.device;

import java.util.List;

/**
 * Policy front for device trust ("My Devices"). Trust is stored in the ONE
 * {@link DeviceRegistry}; this service is the only place that decides what
 * trust MEANS — today: offers from trusted devices auto-accept. Future
 * ecosystem features (clipboard/photo/folder sync, cross-device file access)
 * gate on the same {@link #allowsAutoAccept} / trust checks, which is why the
 * policy is centralized here rather than scattered through mode code.
 */
public final class TrustedDeviceService {

    private final DeviceRegistry registry;

    public TrustedDeviceService(DeviceRegistry registry) {
        this.registry = registry;
    }

    public void trust(String deviceId) {
        registry.setTrusted(deviceId, true);
    }

    public void revoke(String deviceId) {
        registry.setTrusted(deviceId, false);
    }

    public boolean isTrusted(String deviceId) {
        return registry.isTrusted(deviceId);
    }

    /** Should an incoming offer from this device skip the approval popup? */
    public boolean allowsAutoAccept(String deviceId) {
        return registry.isTrusted(deviceId);
    }

    /** "My Devices" view: every trusted device, online or not. */
    public List<DeviceInfo> trustedDevices() {
        return registry.snapshots().stream().filter(DeviceInfo::trusted).toList();
    }
}
