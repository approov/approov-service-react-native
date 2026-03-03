package io.approov.util.sig;

import io.approov.util.http.sfv.StringItem;

/**
 * @author jricher
 */
public class SignatureBaseBuilder {
	private final SignatureParameters sigParams;
	private final ComponentProvider ctx;

	public SignatureBaseBuilder(SignatureParameters sigParams, ComponentProvider ctx) {
		this.sigParams = sigParams;
		this.ctx = ctx;
	}

	public String createSignatureBase() {
		StringBuilder base = new StringBuilder();

		for (StringItem componentIdentifier : sigParams.getComponentIdentifiers()) {

			String componentValue = ctx.getComponentValue(componentIdentifier);

			if (componentValue != null) {
				// write out the line to the base
				componentIdentifier.serializeTo(base)
					.append(": ")
					.append(componentValue)
					.append('\n');
			} else {
				// FIXME: be more graceful about bailing
				throw new RuntimeException("Couldn't find a value for required parameter: " + componentIdentifier.serialize());
			}
		}

		// add the signature parameters line
		sigParams.toComponentIdentifier().serializeTo(base)
			.append(": ");
		sigParams.toComponentValue().serializeTo(base);

		return base.toString();
	}
}
