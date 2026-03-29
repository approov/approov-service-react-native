import Foundation

public typealias OrderedMap<Key: Hashable, Value> = [Key: Value]

public struct PseudoDecimal: Equatable {
    public let value: Double

    public init(_ value: Double) throws {
        self.value = value
    }
}

public enum RFC9651BareItem: Equatable {
    case string(String)
    case undecodedByteSequence(String)
    case bool(Bool)
    case integer(Int64)
    case decimal(PseudoDecimal)
    case date(Int64)
}

public struct Item: Equatable {
    public let rfc9651BareItem: RFC9651BareItem
    public let rfc9651Parameters: OrderedMap<String, RFC9651BareItem>

    public init(bareItem: RFC9651BareItem,
                parameters: OrderedMap<String, RFC9651BareItem>) {
        self.rfc9651BareItem = bareItem
        self.rfc9651Parameters = parameters
    }
}

public typealias BareInnerList = [Item]

public struct InnerList: Equatable {
    public let bareInnerList: BareInnerList
    public let parameters: OrderedMap<String, RFC9651BareItem>

    public init(bareInnerList: BareInnerList = [],
                parameters: OrderedMap<String, RFC9651BareItem> = [:]) {
        self.bareInnerList = bareInnerList
        self.parameters = parameters
    }
}

public enum ItemOrInnerList: Equatable {
    case item(Item)
    case innerList(InnerList)
}

public struct StructuredFieldValueSerializer {
    public init() {}

    public mutating func writeItemFieldValue(_ item: Item) throws -> [UInt8] {
        return Array(serializeItem(item).utf8)
    }

    public mutating func writeListFieldValue(_ list: [ItemOrInnerList]) throws -> [UInt8] {
        return Array(list.map(serializeItemOrInnerList).joined(separator: ", ").utf8)
    }

    public mutating func writeDictionaryFieldValue(_ dictionary: OrderedMap<String, ItemOrInnerList>) throws -> [UInt8] {
        let serialized = dictionary.map { key, value in
            "\(key)=\(serializeItemOrInnerList(value))"
        }.joined(separator: ", ")
        return Array(serialized.utf8)
    }

    private func serializeItemOrInnerList(_ itemOrInnerList: ItemOrInnerList) -> String {
        switch itemOrInnerList {
        case .item(let item):
            return serializeItem(item)
        case .innerList(let innerList):
            let items = innerList.bareInnerList.map(serializeItem).joined(separator: " ")
            return "(\(items))" + serializeParameters(innerList.parameters)
        }
    }

    private func serializeItem(_ item: Item) -> String {
        return serializeBareItem(item.rfc9651BareItem) + serializeParameters(item.rfc9651Parameters)
    }

    private func serializeParameters(_ parameters: OrderedMap<String, RFC9651BareItem>) -> String {
        return parameters.map { key, value in
            switch value {
            case .bool(true):
                return ";\(key)"
            default:
                return ";\(key)=\(serializeBareItem(value))"
            }
        }.joined()
    }

    private func serializeBareItem(_ bareItem: RFC9651BareItem) -> String {
        switch bareItem {
        case .string(let value):
            return "\"\(value)\""
        case .undecodedByteSequence(let value):
            return ":\(value):"
        case .bool(let value):
            return value ? "?1" : "?0"
        case .integer(let value):
            return "\(value)"
        case .decimal(let value):
            return String(value.value)
        case .date(let value):
            return "@\(value)"
        }
    }
}

public struct StructuredFieldValueParser {
    public init(_ data: Data) {
        _ = data
    }

    public mutating func parseDictionaryFieldValue() throws -> OrderedMap<String, ItemOrInnerList> {
        return [:]
    }

    public mutating func parseListFieldValue() throws -> [ItemOrInnerList] {
        return []
    }

    public mutating func parseItemFieldValue() throws -> Item {
        return Item(bareItem: .string(""), parameters: [:])
    }
}
