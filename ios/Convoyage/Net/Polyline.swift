import Foundation
import CoreLocation

/// Google encoded polyline codec (precision 5 by default, 6 also accepted by the server).
enum Polyline {
    static func encode(_ points: [CLLocationCoordinate2D], precision: Int = 5) -> String {
        let factor: Double = pow(10.0, Double(precision))
        var out: String = ""
        var prevLat: Int = 0
        var prevLng: Int = 0
        for p in points {
            let lat: Int = Int((p.latitude * factor).rounded())
            let lng: Int = Int((p.longitude * factor).rounded())
            append(lat - prevLat, to: &out)
            append(lng - prevLng, to: &out)
            prevLat = lat
            prevLng = lng
        }
        return out
    }

    private static func append(_ value: Int, to out: inout String) {
        var n: Int = value < 0 ? ~(value << 1) : (value << 1)
        while n >= 0x20 {
            let chunk: Int = (0x20 | (n & 0x1f)) + 63
            out.append(Character(UnicodeScalar(UInt8(chunk))))
            n >>= 5
        }
        out.append(Character(UnicodeScalar(UInt8(n + 63))))
    }

    static func decode(_ encoded: String, precision: Int = 5) -> [CLLocationCoordinate2D] {
        let factor: Double = pow(10.0, Double(precision))
        let bytes: [UInt8] = Array(encoded.utf8)
        var index: Int = 0
        var lat: Int = 0
        var lng: Int = 0
        var out: [CLLocationCoordinate2D] = []

        func next() -> Int? {
            var result: Int = 0
            var shift: Int = 0
            var byte: Int = 0
            repeat {
                if index >= bytes.count { return nil }
                byte = Int(bytes[index]) - 63
                index += 1
                if byte < 0 || byte > 63 { return nil }
                result |= (byte & 0x1f) << shift
                shift += 5
                if shift > 35 { return nil }
            } while byte >= 0x20
            return (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
        }

        while index < bytes.count {
            guard let dLat = next(), let dLng = next() else { break }
            lat += dLat
            lng += dLng
            out.append(CLLocationCoordinate2D(latitude: Double(lat) / factor, longitude: Double(lng) / factor))
        }
        return out
    }
}
