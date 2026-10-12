package com.contextlayer.backend.budget;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

	private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	public int count(String text) {
		return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
	}
}
