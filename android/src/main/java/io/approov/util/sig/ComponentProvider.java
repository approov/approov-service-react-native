package io.approov.util.sig;

import java.util.List;
import java.util.regex.Pattern;

import io.approov.util.http.sfv.Dictionary;
import io.approov.util.http.sfv.Item;
import io.approov.util.http.sfv.ListElement;
import io.approov.util.http.sfv.ParseException;
import io.approov.util.http.sfv.Parser;
import io.approov.util.http.sfv.StringItem;
import io.approov.util.http.sfv.Type;

/**
 * @author jricher
 *
 */
public interface ComponentProvider {
	Pattern PATTERN_WHITESPACE = Pattern.compile("[\\s\\t]*\\r\\n[\\s\\t]*");

	// Derived component identifiers
	/** The authority of the target URI for a request (Section 2.2.3). */
	String DC_AUTHORITY = "@authority";

	/** The method used for a request (Section 2.2.1). */
	String DC_METHOD = "@method";

	/** The absolute path portion of the target URI for a request (Section 2.2.6). */
	String DC_PATH = "@path";

	/** The query portion of the target URI for a request (Section 2.2.7). */
	String DC_QUERY = "@query";

	/** A parsed and encoded query parameter of the target URI for a request (Section 2.2.8). */
	String DC_QUERY_PARAM = "@query-param";

	/** The request target (Section 2.2.5). */
	String DC_REQUEST_TARGET = "@request-target";

	/** The scheme of the target URI for a request (Section 2.2.4). */
	String DC_SCHEME = "@scheme";

	/** The status code for a response (Section 2.2.9). */
	String DC_STATUS = "@status";

	/** The full target URI for a request (Section 2.2.2). */
	String DC_TARGET_URI = "@target-uri";

	// derived, for requests
	String getMethod();
	String getAuthority();
	String getScheme();
	String getTargetUri();
	String getRequestTarget();
	String getPath();
	String getQuery();
	String getQueryParam(String name);
	boolean hasBody();

	// derived, for responses
	String getStatus();

	// fields
	boolean hasField(String name);

	String getField(String name);

	static String combineFieldValues(List<String> fields) {
		if (fields == null) {
			return null;
		} else {
			StringBuilder sb = new StringBuilder();
			for (String field : fields) {
				String trimmedField = field.trim();
				String replacedField = PATTERN_WHITESPACE.matcher(trimmedField).replaceAll(" ");
				if (sb.length() > 0) {
					sb.append(", ");
				}
				sb.append(replacedField);
			}
			return sb.length() > 0 ? sb.toString() : null;
		}
	}

	default String getComponentValue(StringItem componentIdentifier) {
		String baseIdentifier = componentIdentifier.get();
		if (baseIdentifier.startsWith("@")) {
			// derived component
			switch (baseIdentifier) {
				case DC_METHOD:
					return getMethod();
				case DC_AUTHORITY:
					return getAuthority();
				case DC_SCHEME:
					return getScheme();
				case DC_TARGET_URI:
					return getTargetUri();
				case DC_REQUEST_TARGET:
					return getRequestTarget();
				case DC_PATH:
					return getPath();
				case DC_QUERY:
					return getQuery();
				case DC_STATUS:
					return getStatus();
				case DC_QUERY_PARAM:
				{
					if (componentIdentifier.getParams().containsKey("name")) {
						Item<?> nameParameter = componentIdentifier.getParams().get("name");
						if (nameParameter instanceof StringItem) {
							String name = ((StringItem)nameParameter).get();
							return getQueryParam(name);
						} else {
							throw new IllegalArgumentException("Invalid Syntax: Value for 'name' parameter of " + baseIdentifier + " must be a StringItem");
						}
					} else {
						throw new IllegalArgumentException("'name' parameter of " + baseIdentifier + " is required");
					}
				}
				default:
					throw new IllegalArgumentException("Unknown derived component: " + baseIdentifier);
			}
		} else {
			if (componentIdentifier.getParams().containsKey("key")) {
				Item<?> keyParameter = componentIdentifier.getParams().get("key");
				if (keyParameter instanceof StringItem) {
					try {
						String fieldValue = getField(baseIdentifier);
						Dictionary dictionary = Parser.parseDictionary(fieldValue);
						String key = ((StringItem)keyParameter).get();
						if (dictionary.get().containsKey(key)) {
							ListElement<?> dictionaryValue = dictionary.get().get(key);
							// we always re-serialize the value
							return dictionaryValue.serialize();
						} else {
							throw new IllegalArgumentException("Value for '" + key + "' key of dictionary " + baseIdentifier + " does not exist");
						}
					} catch (ParseException e) {
						throw new IllegalArgumentException("Field " + baseIdentifier + " is not a dictionary field");
					}
				} else {
					throw new IllegalArgumentException("Invalid Syntax: Value for 'key' parameter of field " + baseIdentifier + " must be a StringItem");
				}
			} else if (componentIdentifier.getParams().containsKey("sf")) {
				switch (baseIdentifier) {
				    case "accept":
				    case "accept-encoding":
				    case "accept-language":
				    case "accept-patch":
				    case "accept-ranges":
				    case "access-control-allow-headers":
				    case "access-control-allow-methods":
				    case "access-control-expose-headers":
				    case "access-control-request-headers":
				    case "allow":
				    case "alpn":
				    case "connection":
				    case "content-encoding":
				    case "content-language":
				    case "content-length":
				    case "te":
				    case "timing-allow-origin":
				    case "trailer":
				    case "transfer-encoding":
				    case "vary":
				    case "x-xss-protection":
				    case "cache-status":
				    case "proxy-status":
				    case "variant-key":
				    case "x-list":
				    case "x-list-a":
				    case "x-list-b":
				    case "accept-ch":
				    case "example-list":
				    {
				    	// List
				    	try {
					    	String fieldValue = getField(baseIdentifier);
					    	Type<?> sf = Parser.parseList(fieldValue);
					    	return sf.serialize();
						} catch (ParseException e) {
							throw new IllegalArgumentException("Field " + baseIdentifier + " is not a structured field");
						}
				    }
				    case "alt-svc":
				    case "cache-control":
				    case "expect-ct":
				    case "keep-alive":
				    case "pragma":
				    case "prefer":
				    case "preference-applied":
				    case "surrogate-control":
				    case "variants":
				    case "signature":
				    case "signature-input":
				    case "priority":
				    case "x-dictionary":
				    case "example-dict":
				    case "cdn-cache-control":
				    {
				    	// Dictionary
				    	try {
					    	String fieldValue = getField(baseIdentifier);
					    	Type<?> sf = Parser.parseDictionary(fieldValue);
					    	return sf.serialize();
						} catch (ParseException e) {
							throw new IllegalArgumentException("Field " + baseIdentifier + " is not a structured field");
						}
				    }
				    case "access-control-max-age":
				    case "access-control-allow-credentials":
				    case "access-control-allow-origin":
				    case "access-control-request-method":
				    case "age":
				    case "alt-used":
				    case "content-type":
				    case "cross-origin-resource-policy":
				    case "expect":
				    case "host":
				    case "origin":
				    case "retry-after":
				    case "x-content-type-options":
				    case "x-frame-options":
				    case "example-integer":
				    case "example-decimal":
				    case "example-string":
				    case "example-token":
				    case "example-bytesequence":
				    case "example-boolean":
				    {
				    	// Item
				    	try {
					    	String fieldValue = getField(baseIdentifier);
					    	Type<?> sf = Parser.parseItem(fieldValue);
					    	return sf.serialize();
						} catch (ParseException e) {
							throw new IllegalArgumentException("Field " + baseIdentifier + " is not a structured field");
						}
				    }
				    default:
				    	throw new IllegalArgumentException("Field " + baseIdentifier + " is not a structured field");

				}
			} else {
				return getField(baseIdentifier);
			}
		}
	}
}
