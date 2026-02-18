package io.approov.util.http.sfv;

import java.math.BigDecimal;

/**
 * Marker interface for Items.
 * 
 * @param <T>
 *            represented Java type
 * @see <a href= "https://www.rfc-editor.org/rfc/rfc8941.html#item">Section 3.3
 *      of RFC 8941</a>
 */
public interface Item<T> extends ListElement<T>, Parameterizable<T> {
    Item<T> withParams(Parameters params);

    /**
     * Convert an object of unknown type into the appropriate Item type.
     * <p>
     * Supported types are Integer, Long, String, Boolean, byte[], BigDecimal
     *
     * @param o the object to wrap in an item
     * @return an Item of the appropriate type or null if the provided value is null or not of a
     * supported type.
     */
    static Item<?> asItem(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Item) {
            return (Item<?>) o;
        } else if (o instanceof Integer) {
            return IntegerItem.valueOf(((Integer) o).longValue());
        } else if (o instanceof Long) {
            return IntegerItem.valueOf((Long) o);
        } else if (o instanceof String) {
            return StringItem.valueOf((String) o);
        } else if (o instanceof Boolean) {
            return BooleanItem.valueOf((Boolean) o);
        } else if (o instanceof byte[]) {
            return ByteSequenceItem.valueOf((byte[]) o);
        } else if (o instanceof BigDecimal) {
            return DecimalItem.valueOf((BigDecimal)o);
        }
        return null;
    }

    /**
     * Convert an object of unknown type into the appropriate Item type.
     * <p>
     * Supported types are Integer, Long, String, Boolean, byte[], BigDecimal
     *
     * @param o the object to wrap in an item
     * @param params the parameters to attach to the provided object.
     * @return an Item of the appropriate type with the attached params or null if the provided
     * value is null or not of a supported type.
     */
    static Item<?> asItem(Object o, Parameters params) {
        Item<?> item = asItem(o);
        if (item == null) {
            return null;
        }
        item.withParams(params);
        return item;
    }

    /**
     * Determine if an object of unknown type is of a type supported by Item.asItem(o).
     * <p>
     * Supported types are Integer, Long, String, Boolean, byte[], BigDecimal
     *
     * @param o the object to test
     * @return true if the object is one of the supported types; false otherwise
     */
    static boolean isItemType(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Item) {
            return true;
        } else if (o instanceof Integer) {
            return true;
        } else if (o instanceof Long) {
            return true;
        } else if (o instanceof String) {
            return true;
        } else if (o instanceof Boolean) {
            return true;
        } else if (o instanceof byte[]) {
            return true;
        } else if (o instanceof BigDecimal) {
            return true;
        }
        return false;

    }
}
