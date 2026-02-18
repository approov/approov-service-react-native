package io.approov.util.sig;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.approov.util.http.sfv.Dictionary;
import io.approov.util.http.sfv.InnerList;
import io.approov.util.http.sfv.Item;
import io.approov.util.http.sfv.ListElement;
import io.approov.util.http.sfv.NumberItem;
import io.approov.util.http.sfv.Parameters;
import io.approov.util.http.sfv.StringItem;

/**
 * Carrier class for signature parameters.
 *
 * @author jricher
 * @author jexh
 */
public class SignatureParameters implements Cloneable {
	private static final String ALG = "alg";
	private static final String CREATED = "created";
	private static final String EXPIRES = "expires";
	private static final String KEYID = "keyid";
	private static final String NONCE = "nonce";
	private static final String TAG = "tag";

	// set this to add an extra header to the request that includes the SHA256 of the signature base
	// which can be used to aid debugging on the server side to determine if there is a problem with
	// the reconstruction of the signature base or the verification of the signature.
	private boolean debugMode;

	private List<StringItem> componentIdentifiers;

	// this preserves insertion order
	private Map<String, Object> parameters;

	/**
	 * Default constructor creates an empty SignatureParameters ready to be populated.
	 */
	public SignatureParameters() {
		componentIdentifiers = new ArrayList<>();
		parameters = new LinkedHashMap<>();
	}

	/**
	 * Copy constructor creates a SignatureParameters instance pre-populated with a copy of all the
	 * component identifiers and parameters from the provided base.
	 *
	 * @param base
	 */
	public SignatureParameters(SignatureParameters base) {
		// Items are immutable, it's fine to reference the items from the original component
		// identifiers list
		componentIdentifiers = new ArrayList<>(base.componentIdentifiers);
		// Parameters are immutable (except for custom params with byte arrays but we ignore those)
		parameters = new LinkedHashMap<>(base.parameters);
	}

	/**
	 * @return the componentIdentifiers
	 */
	List<StringItem> getComponentIdentifiers() {
		return componentIdentifiers;
	}

	/**
	 * @return the parameters
	 */
	Map<String, Object> getParameters() {
		return parameters;
	}

	/**
	 * @param parameters the parameters to set
	 */
	public SignatureParameters setParameters(Map<String, Object> parameters) {
		this.parameters = parameters;
		return this;
	}

	/**
	 * Determine if debug mode has been set for this signature parameters instance
	 *
	 * @return true is debug mode is on; false otherwise
	 */
	public boolean isDebugMode() {
		return debugMode;
	}

	/**
	 * Set the debug mode for this signature parameters
	 *
	 * @param debugMode true to enable; false to disable
	 */
	public void setDebugMode(boolean debugMode) {
		this.debugMode = debugMode;
	}

	/**
	 * @return the alg
	 */
	public String getAlg() {
		return (String) getParameters().get(ALG);
	}

	/**
	 * @param alg the alg to set
	 */
	public SignatureParameters setAlg(String alg) {
		getParameters().put(ALG, alg);
		return this;
	}

	/**
	 * @return the created
	 */
	public Long getCreated() {
		return (Long) getParameters().get(CREATED);
	}

	/**
	 * @param created the created to set
	 */
	public SignatureParameters setCreated(Long created) {
		getParameters().put(CREATED, created);
		return this;
	}

	/**
	 * @return the expires
	 */
	public Long getExpires() {
		return (Long) getParameters().get(EXPIRES);
	}

	/**
	 * @param expires the expires to set
	 */
	public SignatureParameters setExpires(Long expires) {
		getParameters().put(EXPIRES, expires);
		return this;
	}

	/**
	 * @return the keyid
	 */
	public String getKeyid() {
		return (String) getParameters().get(KEYID);
	}

	/**
	 * @param keyid the keyid to set
	 */
	public SignatureParameters setKeyid(String keyid) {
		getParameters().put(KEYID, keyid);
		return this;
	}

	/**
	 * @return the nonce
	 */
	public String getNonce() {
		return (String) getParameters().get(NONCE);
	}

	/**
	 * @param nonce the nonce to set
	 */
	public SignatureParameters setNonce(String nonce) {
		getParameters().put(NONCE, nonce);
		return this;
	}

	public String getTag() {
		return (String) getParameters().get(TAG);
	}

	public SignatureParameters setTag(String tag) {
		getParameters().put(TAG, tag);
		return this;
	}

	public Object getCustomParameter(String key) {
		return getParameters().get(key);
	}

	public Object setCustomParameter(String key, Object value) {
		switch (key) {
			case ALG: {
				String val = (String)value;
				setAlg(val);
				break;
			}
			case CREATED: {
				Long val = (Long)value;
				setCreated(val);
				break;
			}
			case EXPIRES: {
				Long val = (Long)value;
				setExpires(val);
				break;
			}
			case KEYID: {
				String val = (String)value;
				setKeyid(val);
				break;
			}
			case NONCE: {
				String val = (String)value;
				setNonce(val);
				break;
			}
			case TAG: {
				String val = (String)value;
				setTag(val);
				break;
			}
			default: {
				if (!Item.isItemType(value)) {
					throw new IllegalArgumentException("Parameter value of unsupported type: " + value.getClass());
				}
				getParameters().put(key, value);
			}
		}
		return this;
	}
	public StringItem toComponentIdentifier() {
		return StringItem.valueOf("@signature-params");
	}

	public InnerList toComponentValue() {

		// take a copy of the identifiers
		List<Item<?>> identifiers = new ArrayList<>(componentIdentifiers.size());
		identifiers.addAll(componentIdentifiers);
		InnerList list = InnerList.valueOf(identifiers);

		// take a copy of the parameters
		Map<String, Object> params = new LinkedHashMap<>(getParameters());
		list = list.withParams(Parameters.valueOf(params));

		return list;
	}

	/**
	 * Add a component without parameters.
	 */
	public SignatureParameters addComponentIdentifier(String identifier) {
		if (!identifier.startsWith("@")) {
			componentIdentifiers.add(StringItem.valueOf(identifier.toLowerCase(Locale.US)));
		} else {
			componentIdentifiers.add(StringItem.valueOf(identifier));
		}
		return this;
	}

	/**
	 * Add a component with optional parameters. Field components are assumed to be
	 * already set to lowercase.
	 */
	public SignatureParameters addComponentIdentifier(StringItem identifier) {
		componentIdentifiers.add(identifier);
		return this;
	}

	// this ignores parameters
	public boolean containsComponentIdentifier(String identifier) {
		for (StringItem item : componentIdentifiers) {
			if (item.get().equals(identifier)) {
				return true;
			}
		}
		return false;
	}

	// does not ignore parameters
	public boolean containsComponentIdentifier(StringItem identifier) {
		String value = identifier.get();
		Parameters params = identifier.getParams();
		for (StringItem item : componentIdentifiers) {
			if (value.equals(identifier.get())
				&& params.equals(identifier.getParams())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param signatureInput
	 * @param sigId
	 */
	public static SignatureParameters fromDictionaryEntry(Dictionary signatureInput, String sigId) {
		if (signatureInput.get().containsKey(sigId)) {
			ListElement<?> item = signatureInput.get().get(sigId);
			if (item instanceof InnerList) {
				InnerList coveredComponents = (InnerList)item;
				SignatureParameters params = new SignatureParameters();
				for (Item<?> innerItem : coveredComponents.get()) {
					params.addComponentIdentifier((StringItem)innerItem);
				}
				for (Map.Entry<String, Item<?>> entry : coveredComponents.getParams().entrySet()) {
					String key = entry.getKey();
                    switch (key) {
						case ALG: {
							String value = ((StringItem) entry.getValue()).get();
							params.setAlg(value);
							break;
						}
                        case CREATED: {
							Long value = ((NumberItem<?>) entry.getValue()).getAsLong();
							params.setCreated(value);
							break;
						}
                        case EXPIRES: {
							Long value = ((NumberItem<?>) entry.getValue()).getAsLong();
							params.setExpires(value);
							break;
						}
                        case KEYID: {
							String value = ((StringItem) entry.getValue()).get();
							params.setKeyid(value);
							break;
						}
                        case NONCE: {
							String value = ((StringItem) entry.getValue()).get();
							params.setNonce(value);
							break;
						}
                        case TAG: {
							String value = ((StringItem) entry.getValue()).get();
							params.setTag(value);
							break;
						}
                        default: {
							Object value = entry.getValue().get();
							params.getParameters().put(key, value);
							break;
						}
                    }
				}
				return params;
			} else {
				throw new IllegalArgumentException("Invalid syntax, identifier '" + sigId + "' must be an inner list");
			}
		} else {
			throw new IllegalArgumentException("Could not find identifier '" + sigId + "' in dictionary " + signatureInput.serialize());
		}
	}

	@NonNull
	@Override
	public String toString() {
		return "SignatureParameters: " + toComponentValue().serialize();
	}
}
