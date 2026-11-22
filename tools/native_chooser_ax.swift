// Opt-in fixture driver. Python validates the exact owned JVM/directory before each invocation.
// AXUIElementCreateApplication avoids System Events' ambiguous references for repeated Java apps.
import AppKit
import ApplicationServices
import Dispatch
import Foundation

struct Refusal: Error { let message: String }
struct UnconfirmedPress: Error { let status: Int32 }

func attribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
    var value: CFTypeRef?
    return AXUIElementCopyAttributeValue(element, name as CFString, &value) == .success ? value : nil
}

func text(_ element: AXUIElement, _ name: String) -> String {
    attribute(element, name) as? String ?? ""
}

func children(_ element: AXUIElement) -> [AXUIElement] {
    attribute(element, kAXChildrenAttribute) as? [AXUIElement] ?? []
}

func find(_ root: AXUIElement, _ predicate: (AXUIElement) -> Bool) -> AXUIElement? {
    var pending: [(AXUIElement, Int)] = [(root, 0)]
    var index = 0
    while index < pending.count && index < 2048 {
        let (element, depth) = pending[index]
        index += 1
        if predicate(element) { return element }
        if depth < 10 { pending += children(element).prefix(256).map { ($0, depth + 1) } }
    }
    return nil
}

func button(_ root: AXUIElement, _ title: String) -> AXUIElement? {
    find(root) { text($0, kAXRoleAttribute) == "AXButton" && text($0, kAXTitleAttribute) == title }
}

func press(_ element: AXUIElement) throws {
    guard attribute(element, kAXEnabledAttribute) as? Bool == true else {
        throw Refusal(message: "The expected native control is disabled or unavailable")
    }
    let outcome = AXUIElementPerformAction(element, kAXPressAction as CFString)
    guard outcome == .success else {
        throw UnconfirmedPress(status: outcome.rawValue)
    }
}

func key(_ pid: pid_t, _ code: CGKeyCode, _ flags: CGEventFlags = []) throws {
    guard NSWorkspace.shared.frontmostApplication?.processIdentifier == pid else {
        throw Refusal(message: "The owned native application is not frontmost: expected \(pid), observed \(NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1)")
    }
    guard let down = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true),
          let up = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false) else {
        throw Refusal(message: "Native key events unavailable")
    }
    down.flags = flags
    up.flags = flags
    // AppKit's remote open/save panel receives the front application's native event stream.
    // Process-directed events bypass that panel; never post globally without the identity check.
    down.post(tap: .cghidEventTap)
    up.post(tap: .cghidEventTap)
}

func folderInput(_ app: AXUIElement, _ dialog: AXUIElement, _ accept: String,
                 _ pid: pid_t) throws -> AXUIElement {
    let deadline = DispatchTime.now().uptimeNanoseconds + 2_000_000_000
    var sheetCount = 0
    repeat {
        guard NSWorkspace.shared.frontmostApplication?.processIdentifier == pid else {
            throw Refusal(message: "The owned application lost focus while opening Go to Folder")
        }
        let windows = attribute(app, kAXWindowsAttribute) as? [AXUIElement] ?? []
        let sheets = windows.compactMap { window in
            find(window, { !CFEqual($0, dialog) && text($0, kAXRoleAttribute) == "AXSheet"
                && button($0, accept) == nil })
        }
        sheetCount = sheets.count
        if sheets.count == 1, let sheet = sheets.first,
           let input = find(sheet, { text($0, kAXRoleAttribute) == "AXTextField" })
                ?? find(sheet, { text($0, kAXRoleAttribute) == "AXComboBox" }) {
            var writable = DarwinBoolean(false)
            if AXUIElementIsAttributeSettable(input, kAXValueAttribute as CFString, &writable) == .success,
               writable.boolValue { return input }
        }
        usleep(50_000)
    } while DispatchTime.now().uptimeNanoseconds < deadline
    throw Refusal(message: "Native Go to Folder input unavailable; observed \(sheetCount) sheets")
}

do {
    guard CommandLine.arguments.count == 5, let pid = pid_t(CommandLine.arguments[1]), pid > 0 else {
        throw Refusal(message: "Expected owned PID, action, selected fixture path and native accept label")
    }
    let accept = CommandLine.arguments[4]
    guard ["Open", "Choose"].contains(accept) else { throw Refusal(message: "Unknown native picker backend") }
    let action = CommandLine.arguments[2]
    let selected = CommandLine.arguments[3]
    let app = AXUIElementCreateApplication(pid)
    var actual: pid_t = 0
    guard AXUIElementGetPid(app, &actual) == .success, actual == pid else {
        throw Refusal(message: "Native application identity differs")
    }
    AXUIElementSetMessagingTimeout(app, 2)
    let windows = attribute(app, kAXWindowsAttribute) as? [AXUIElement] ?? []
    var dialogs: [AXUIElement] = []
    for window in windows {
        if let candidate = find(window, { node in
            let role = text(node, kAXRoleAttribute)
            let subrole = text(node, kAXSubroleAttribute)
            return (role == "AXSheet" || subrole == "AXDialog")
                && button(node, "Cancel") != nil && button(node, accept) != nil
        }) { dialogs.append(candidate) }
    }
    guard dialogs.count == 1, let dialog = dialogs.first else {
        let data = try JSONSerialization.data(withJSONObject: ["status": "absent", "pid": actual,
            "windows": windows.map { text($0, kAXTitleAttribute) }], options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
        exit(2)
    }
    let title = text(dialog, kAXTitleAttribute)
    let role = text(dialog, kAXRoleAttribute)
    var dialogPID: pid_t = 0
    AXUIElementGetPid(dialog, &dialogPID)
    let visibleWindows = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String:Any]] ?? []
    let visibleCount = visibleWindows.filter { ($0[kCGWindowOwnerPID as String] as? Int32) == pid }.count
    if action == "visible" {
        // Read-only observation; selection remains entirely unchanged.
    } else if ["cancel", "cancel-abandoned", "cancel-final"].contains(action) {
        try press(button(dialog, "Cancel")!)
    } else if action == "select" {
        guard NSRunningApplication(processIdentifier: pid)?.activate(options: []) == true else {
            throw Refusal(message: "Owned native application could not activate")
        }
        AXUIElementSetAttributeValue(app, kAXFrontmostAttribute as CFString, kCFBooleanTrue)
        for _ in 0..<20 {
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == pid { break }
            usleep(50_000)
        }
        try key(pid, 5, [.maskCommand, .maskShift]) // Native Go to Folder.
        let input = try folderInput(app, dialog, accept, pid)
        let valueStatus = AXUIElementSetAttributeValue(input, kAXValueAttribute as CFString, selected as CFString)
        let focusStatus = AXUIElementSetAttributeValue(input, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        guard valueStatus == .success, focusStatus == .success else {
            throw Refusal(message: "Native Go to Folder input refused value/focus: \(valueStatus.rawValue)/\(focusStatus.rawValue)")
        }
        try key(pid, 36)
        usleep(400_000)
        guard let open = button(dialog, accept) else {
            throw Refusal(message: "Native file selection control disappeared")
        }
        try press(open)
    } else {
        throw Refusal(message: "Unknown native fixture action")
    }
    let data = try JSONSerialization.data(withJSONObject: ["status": "performed", "pid": actual,
        "role": role, "window": title, "action": action, "dialogPid": dialogPID,
        "onScreenWindowCount": visibleCount], options: [.sortedKeys])
    print(String(decoding: data, as: UTF8.self))
} catch {
    if let failed = error as? UnconfirmedPress {
        let data = try JSONSerialization.data(withJSONObject: ["status": "unconfirmed",
            "pid": Int(CommandLine.arguments[1])!, "action": CommandLine.arguments[2],
            "axStatus": failed.status], options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
        exit(3)
    }
    fputs("Native chooser action refused: \(error)\n", stderr)
    exit(1)
}
