import MapKit
import SwiftUI
@preconcurrency import ZenTaskShared

/// Saved places for location reminders: Home, Work and others, each with a map point, radius and Wi‑Fi names.
/// On the Mac only Wi‑Fi is used (arrive = join the place's Wi‑Fi); the phone also uses the map point.
struct PlacesSheet: View {
    @Environment(TasksModel.self) private var tasks
    @Environment(\.dismiss) private var dismiss
    @State private var editing: Place?
    @State private var creating: String?
    @State private var places: [Place] = []

    var body: some View {
        Group {
            if editing != nil || creating != nil {
                PlaceEditor(original: editing, kind: creating ?? editing?.kind ?? Place.companion.KIND_CUSTOM) {
                    editing = nil; creating = nil; reload()
                }
            } else {
                list
            }
        }
        .frame(width: Theme.Places.width, height: Theme.Places.height)
        .onAppear(perform: reload)
    }

    private func reload() { places = tasks.core.placeActions.list() }

    private var list: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text("Places").font(.title3.weight(.semibold))
            Text("Remind about a task when you arrive at or leave a place. On this Mac that means joining or leaving the place's Wi‑Fi; your phone also uses its location.")
                .font(.callout).foregroundStyle(.secondary)
            if !WifiPlaces.shared.authorized {
                HStack {
                    Label("macOS needs Location Services to read the Wi‑Fi name.", systemImage: "location.slash").foregroundStyle(.orange)
                    Spacer()
                    Button("Allow…") { WifiPlaces.shared.requestPermission() }
                }
            }
            List {
                ForEach(places, id: \.id) { p in
                    HStack {
                        Image(systemName: Self.symbol(p.kind)).foregroundStyle(.tint).frame(width: Theme.Space.l)
                        VStack(alignment: .leading) {
                            Text(p.name)
                            Text(p.wifi.isEmpty ? "No Wi‑Fi names: only your phone can tell when you're here" : "Wi‑Fi: " + p.wifi.joined(separator: ", "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Edit…") { editing = p }
                    }
                }
            }
            .listStyle(.inset)
            HStack {
                if !places.contains(where: { $0.kind == Place.companion.KIND_HOME }) { Button("Add Home", systemImage: "house") { creating = Place.companion.KIND_HOME } }
                if !places.contains(where: { $0.kind == Place.companion.KIND_WORK }) { Button("Add Work", systemImage: "building.2") { creating = Place.companion.KIND_WORK } }
                Button("Add place", systemImage: "plus") { creating = Place.companion.KIND_CUSTOM }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
    }

    static func symbol(_ kind: String) -> String {
        switch kind { case Place.companion.KIND_HOME: "house.fill"; case Place.companion.KIND_WORK: "building.2.fill"; default: "mappin.circle.fill" }
    }
}

private struct PlaceEditor: View {
    @Environment(TasksModel.self) private var tasks
    let original: Place?
    let kind: String
    let done: () -> Void
    @State private var name = ""
    @State private var placeKind = ""
    @State private var point: CLLocationCoordinate2D?
    @State private var radius = 150.0
    @State private var wifi: [String] = []
    @State private var newWifi = ""
    @State private var camera: MapCameraPosition = .automatic
    @State private var confirmDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text(original == nil ? "New place" : "Edit place").font(.title3.weight(.semibold))
            HStack {
                TextField("Name", text: $name).textFieldStyle(.roundedBorder)
                Picker("Kind", selection: $placeKind) {
                    Text("Home").tag(Place.companion.KIND_HOME)
                    Text("Work").tag(Place.companion.KIND_WORK)
                    Text("Other").tag(Place.companion.KIND_CUSTOM)
                }
                .pickerStyle(.segmented).labelsHidden().fixedSize()
            }
            Text("Click the map to set the place (your phone uses it to tell when you arrive or leave).").font(.caption).foregroundStyle(.secondary)
            MapReader { proxy in
                Map(position: $camera) {
                    if let point {
                        MapCircle(center: point, radius: radius).foregroundStyle(Color.themeAccent.opacity(0.2)).stroke(Color.themeAccent, lineWidth: 2)
                        Marker(name.isEmpty ? "Place" : name, coordinate: point)
                    }
                }
                .onTapGesture { location in if let c = proxy.convert(location, from: .local) { point = c } }
            }
            .frame(height: Theme.Places.mapHeight)
            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            HStack {
                Text("Radius \(Int(radius)) m").monospacedDigit().frame(width: Theme.Places.radiusLabel, alignment: .leading)
                Slider(value: $radius, in: 50...1000)
            }
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                Text("Wi‑Fi here").font(.headline)
                HStack {
                    ForEach(wifi, id: \.self) { w in
                        Button { wifi.removeAll { $0 == w } } label: { Label(w, systemImage: "xmark.circle.fill") }.help("Remove")
                    }
                }
                HStack {
                    TextField("Wi‑Fi name", text: $newWifi).textFieldStyle(.roundedBorder).onSubmit(addWifi)
                    Button("Add", action: addWifi)
                    if let current = WifiPlaces.shared.ssid, !wifi.contains(current) {
                        Button("Add current (\(current))") { wifi.append(current) }
                    }
                }
            }
            HStack {
                Button("Cancel", role: .cancel, action: done).keyboardShortcut(.cancelAction)
                if original != nil { Button("Delete…", role: .destructive) { confirmDelete = true } }
                Spacer()
                Button("Save", action: save).keyboardShortcut(.defaultAction).disabled(point == nil && wifi.isEmpty)
            }
            .controlSize(.large)
        }
        .padding(Theme.Space.xl)
        .onAppear {
            name = original?.name ?? PlaceActions.companion.defaultName(kind: kind)
            placeKind = original?.kind ?? kind
            radius = original?.radius ?? 150
            wifi = original?.wifi ?? []
            if let o = original, o.latitude != 0 || o.longitude != 0 {
                let c = CLLocationCoordinate2D(latitude: o.latitude, longitude: o.longitude)
                point = c
                camera = .region(MKCoordinateRegion(center: c, latitudinalMeters: 1500, longitudinalMeters: 1500))
            } else {
                camera = .userLocation(fallback: .region(MKCoordinateRegion(center: .init(latitude: 10.7769, longitude: 106.7009), latitudinalMeters: 4000, longitudinalMeters: 4000)))
            }
        }
        .confirmationDialog("Delete \"\(original?.name ?? "")\"?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { if let o = original { tasks.core.placeActions.delete(id: o.id) }; done() }
        } message: { Text("Tasks that remind there lose their location reminder.") }
    }

    private func addWifi() {
        let w = newWifi.trimmingCharacters(in: .whitespaces)
        if !w.isEmpty, !wifi.contains(w) { wifi.append(w) }
        newWifi = ""
    }

    private func save() {
        let lat = point?.latitude ?? original?.latitude ?? 0
        let lon = point?.longitude ?? original?.longitude ?? 0
        if let o = original {
            tasks.core.placeActions.update(original: o, name: name, kind: placeKind, latitude: lat, longitude: lon, radius: radius, wifi: wifi)
        } else {
            _ = tasks.core.placeActions.create(name: name, kind: placeKind, latitude: lat, longitude: lon, radius: radius, wifi: wifi)
        }
        done()
    }
}

/// Task detail chip: remind when arriving at / leaving a saved place.
struct LocationChip: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask

    var body: some View {
        let places = tasks.core.placeActions.list()
        let current = places.first { $0.id == task.placeId }
        Menu {
            ForEach(places, id: \.id) { p in
                Menu(p.name) {
                    Button("When I arrive") { tasks.core.actions.setLocationReminder(id: task.id, placeId: p.id, trigger: Place.companion.ARRIVE) }
                    Button("When I leave") { tasks.core.actions.setLocationReminder(id: task.id, placeId: p.id, trigger: Place.companion.LEAVE) }
                }
            }
            if places.isEmpty { Text("No places yet") }
            Divider()
            Button("Manage places…") { tasks.sheet = .places }
            if current != nil { Button("No location reminder") { tasks.core.actions.setLocationReminder(id: task.id, placeId: nil, trigger: nil) } }
        } label: {
            Chip(text: current.map { (task.placeTrigger == Place.companion.LEAVE ? "Leaving " : "At ") + $0.name } ?? "Location",
                 symbol: current.map { PlacesSheet.symbol($0.kind) } ?? "mappin.and.ellipse", tint: .themeAccent, filled: current != nil)
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
        .help("Remind when you arrive at or leave a place")
    }
}
