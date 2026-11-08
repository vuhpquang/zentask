// Renders the placeholder ZenTask app icon set: swift scripts/make-icon.swift <AppIcon.appiconset>
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let sizes: [(Int, Int)] = [(16,1),(16,2),(32,1),(32,2),(128,1),(128,2),(256,1),(256,2),(512,1),(512,2)]
var images: [[String: String]] = []

func render(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(px), inset = s * 0.1, r = NSRect(x: inset, y: inset, width: s - 2*inset, height: s - 2*inset)
    let bg = NSBezierPath(roundedRect: r, xRadius: r.width * 0.225, yRadius: r.width * 0.225)
    NSGradient(starting: NSColor(srgbRed: 0.42, green: 0.55, blue: 0.98, alpha: 1),
               ending: NSColor(srgbRed: 0.27, green: 0.36, blue: 0.85, alpha: 1))!.draw(in: bg, angle: -90)
    let check = NSBezierPath()
    check.move(to: NSPoint(x: s*0.32, y: s*0.50))
    check.line(to: NSPoint(x: s*0.45, y: s*0.37))
    check.line(to: NSPoint(x: s*0.69, y: s*0.63))
    check.lineWidth = s * 0.075; check.lineCapStyle = .round; check.lineJoinStyle = .round
    NSColor.white.setStroke(); check.stroke()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for (pt, scale) in sizes {
    let name = "icon_\(pt)x\(pt)\(scale == 2 ? "@2x" : "").png"
    try! render(pt * scale).write(to: out.appendingPathComponent(name))
    images.append(["idiom": "mac", "size": "\(pt)x\(pt)", "scale": "\(scale)x", "filename": name])
}
let json: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try! JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
    .write(to: out.appendingPathComponent("Contents.json"))
