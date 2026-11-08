// Renders the ZenTask logo: a brush-stroke ensō whose gap is closed by a check mark, on a blue→violet squircle.
//   swift scripts/make-icon.swift <AppIcon.appiconset> [logo.png]
// Writes every macOS icon size + Contents.json, and optionally a 1024 px logo PNG.
import AppKit

let args = CommandLine.arguments
guard args.count >= 2 else { fatalError("usage: make-icon.swift <AppIcon.appiconset> [logo.png]") }
let iconSet = URL(fileURLWithPath: args[1])
let logoURL = args.count >= 3 ? URL(fileURLWithPath: args[2]) : nil

func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: r / 255, green: g / 255, blue: b / 255, alpha: a)
}

// Palette (ZenTask's own): blue → violet background, near-white ink.
let blue = rgb(59, 130, 246)
let indigo = rgb(99, 102, 241)
let violet = rgb(139, 92, 246)
let deep = rgb(76, 29, 149)
let ink = rgb(255, 255, 255)
let inkShade = rgb(224, 231, 255)

/// Polygon of a stroke along an arc whose width follows `width(t)`, t in 0...1.
func brushArc(center c: NSPoint, radius r: CGFloat, from a0: CGFloat, to a1: CGFloat,
              steps: Int = 240, width: (CGFloat) -> CGFloat) -> NSBezierPath {
    var outer: [NSPoint] = [], inner: [NSPoint] = []
    for i in 0...steps {
        let t = CGFloat(i) / CGFloat(steps)
        let a = a0 + (a1 - a0) * t
        // Slight wobble in radius so it reads as hand-drawn, not geometric.
        let rr = r * (1 + 0.012 * sin(t * .pi * 3))
        let w = width(t) / 2
        outer.append(NSPoint(x: c.x + (rr + w) * cos(a), y: c.y + (rr + w) * sin(a)))
        inner.append(NSPoint(x: c.x + (rr - w) * cos(a), y: c.y + (rr - w) * sin(a)))
    }
    let p = NSBezierPath()
    p.move(to: outer[0])
    outer.dropFirst().forEach { p.line(to: $0) }
    inner.reversed().forEach { p.line(to: $0) }
    p.close()
    return p
}

func render(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!
    NSGraphicsContext.current = ctx
    ctx.imageInterpolation = .high
    ctx.cgContext.setShouldAntialias(true)

    let s = CGFloat(px)
    // macOS icon grid: 824/1024 body, centred.
    let inset = s * 100 / 1024
    let body = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    let squircle = NSBezierPath(roundedRect: body, xRadius: body.width * 0.225, yRadius: body.width * 0.225)

    // Soft drop shadow under the body.
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
    shadow.shadowBlurRadius = s * 0.025
    shadow.shadowOffset = NSSize(width: 0, height: -s * 0.01)
    shadow.set()
    deep.setFill()
    squircle.fill()
    NSGraphicsContext.restoreGraphicsState()

    // Diagonal blue → indigo → violet, a deeper violet corner, a soft light from the top-left.
    NSGradient(colors: [blue, indigo, violet], atLocations: [0, 0.5, 1], colorSpace: .sRGB)!.draw(in: squircle, angle: -45)
    NSGraphicsContext.saveGraphicsState()
    squircle.addClip()
    let corner = NSPoint(x: body.maxX, y: body.minY)
    NSGradient(colors: [deep.withAlphaComponent(0.55), deep.withAlphaComponent(0)])!
        .draw(fromCenter: corner, radius: 0, toCenter: corner, radius: body.width * 0.8, options: [])
    let glowCenter = NSPoint(x: body.minX + body.width * 0.3, y: body.maxY)
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.30), NSColor.white.withAlphaComponent(0)])!
        .draw(fromCenter: glowCenter, radius: 0, toCenter: glowCenter, radius: body.width * 0.8, options: [])
    // Thin inner highlight along the edge.
    let rim = NSBezierPath(roundedRect: body.insetBy(dx: s * 0.006, dy: s * 0.006), xRadius: body.width * 0.22, yRadius: body.width * 0.22)
    rim.lineWidth = s * 0.006
    NSColor.white.withAlphaComponent(0.22).setStroke()
    rim.stroke()
    NSGraphicsContext.restoreGraphicsState()

    // A faint halo behind the mark.
    NSGraphicsContext.saveGraphicsState()
    squircle.addClip()
    let mid = NSPoint(x: body.midX, y: body.midY)
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.16), NSColor.white.withAlphaComponent(0)])!
        .draw(fromCenter: mid, radius: 0, toCenter: mid, radius: body.width * 0.42, options: [])
    NSGraphicsContext.restoreGraphicsState()

    // Ink with a faint shadow for depth.
    NSGraphicsContext.saveGraphicsState()
    let inkShadow = NSShadow()
    inkShadow.shadowColor = deep.withAlphaComponent(0.45)
    inkShadow.shadowBlurRadius = s * 0.012
    inkShadow.shadowOffset = NSSize(width: 0, height: -s * 0.006)
    inkShadow.set()
    ink.setFill()
    ink.setStroke()

    let c = NSPoint(x: s * 0.5, y: s * 0.5)
    let r = s * 0.255
    // Ensō: starts just past the upper-right gap, sweeps counter-clockwise almost all the way round.
    // Brush: enters thin, swells through the bottom, lifts off with a dry taper.
    let start = CGFloat(62) * .pi / 180
    let end = CGFloat(62 + 318) * .pi / 180
    let maxW = s * 0.068
    let enso = brushArc(center: c, radius: r, from: start, to: end) { t in
        let enter = min(1, t / 0.12)
        let exit = min(1, (1 - t) / 0.22)
        let swell = 0.86 + 0.14 * sin(t * .pi * 0.9)
        return maxW * swell * pow(enter, 0.6) * pow(exit, 0.8) + s * 0.004
    }
    NSGraphicsContext.saveGraphicsState()
    enso.addClip()
    NSGradient(starting: ink, ending: inkShade)!.draw(in: enso.bounds, angle: -90)
    NSGraphicsContext.restoreGraphicsState()

    // Check mark: short stroke, then a long stroke that exits through the ensō's gap.
    let check = NSBezierPath()
    check.move(to: NSPoint(x: c.x - s * 0.105, y: c.y + s * 0.005))
    check.line(to: NSPoint(x: c.x - s * 0.030, y: c.y - s * 0.075))
    check.line(to: NSPoint(x: c.x + s * 0.205, y: c.y + s * 0.200))
    check.lineWidth = s * 0.056
    check.lineCapStyle = .round
    check.lineJoinStyle = .round
    check.stroke()
    NSGraphicsContext.restoreGraphicsState()

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

let sizes: [(Int, Int)] = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]
var images: [[String: String]] = []
for (pt, scale) in sizes {
    let name = "icon_\(pt)x\(pt)\(scale == 2 ? "@2x" : "").png"
    try! render(pt * scale).write(to: iconSet.appendingPathComponent(name))
    images.append(["idiom": "mac", "size": "\(pt)x\(pt)", "scale": "\(scale)x", "filename": name])
}
let json: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try! JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
    .write(to: iconSet.appendingPathComponent("Contents.json"))

if let logoURL {
    try! FileManager.default.createDirectory(at: logoURL.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! render(1024).write(to: logoURL)
}
