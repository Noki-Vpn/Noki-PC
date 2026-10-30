//go:build windows && with_gvisor

package tun

import (
    "fmt"
    "os"
    "testing"
    "time"

    "golang.org/x/sys/windows/registry"
)

func TestPCWindowsDNSPolicyRemovesOnlyLegacyOwnedRules(t *testing.T) {
    // Isolated HKCU fixtures never touch the machine's DNS policy.
    base := fmt.Sprintf(`Software\NokiDNSFixture-%d-%d`, os.Getpid(), time.Now().UnixNano())
    localPath, groupPath := base+`\local`, base+`\group`
    t.Cleanup(func() {
        _, _ = removeOwnedDNSPolicyAt(registry.CURRENT_USER, localPath, groupPath)
        _ = registry.DeleteKey(registry.CURRENT_USER, groupPath+`\Foreign`)
        _ = registry.DeleteKey(registry.CURRENT_USER, localPath)
        _ = registry.DeleteKey(registry.CURRENT_USER, groupPath)
        _ = registry.DeleteKey(registry.CURRENT_USER, base)
    })
    changed, err := removeOwnedDNSPolicyAt(registry.CURRENT_USER, localPath, groupPath)
    if err != nil || changed { t.Fatalf("missing stores must remain absent: %v %v", changed, err) }
    for _, path := range []string{localPath+`\`+nrptPrefix+"0", groupPath+`\`+nrptPrefix+"1", groupPath+`\Foreign`} {
        key, _, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
        if err != nil { t.Fatal(err) }
        if err = key.SetStringValue("sentinel", "untouched"); err != nil { t.Fatal(err) }
        key.Close()
    }
    changed, err = removeOwnedDNSPolicyAt(registry.CURRENT_USER, localPath, groupPath)
    if err != nil || !changed { t.Fatalf("legacy cleanup: %v %v", changed, err) }
    foreign, err := registry.OpenKey(registry.CURRENT_USER, groupPath+`\Foreign`, registry.READ)
    if err != nil { t.Fatal("foreign rule removed: ", err) }
    value, _, err := foreign.GetStringValue("sentinel")
    foreign.Close()
    if err != nil || value != "untouched" { t.Fatal("foreign rule changed") }
    changed, err = removeOwnedDNSPolicyAt(registry.CURRENT_USER, localPath, groupPath)
    if err != nil || changed { t.Fatalf("repeated cleanup must not refresh policy: %v %v", changed, err) }
    if err = registry.DeleteKey(registry.CURRENT_USER, groupPath+`\Foreign`); err != nil { t.Fatal(err) }
    key, _, err := registry.CreateKey(registry.CURRENT_USER, groupPath+`\`+nrptPrefix+"2", registry.ALL_ACCESS)
    if err != nil { t.Fatal(err) }
    key.Close()
    if _, err = removeOwnedDNSPolicyAt(registry.CURRENT_USER, localPath, groupPath); err != nil { t.Fatal(err) }
    if key, err = registry.OpenKey(registry.CURRENT_USER, groupPath, registry.READ); err == nil {
        key.Close()
        t.Fatal("empty legacy GP container still overrides local DNS policies")
    }
}
