package com.contextlayer.backend.budget;

public enum DetailLevel {
	PATH_ONLY(0.10),
	SUMMARY(0.35),
	OUTLINE(0.50),
	CODE_1(0.70),
	FOCUSED(0.80),
	CODE_2(0.85),
	CODE_3(0.95);

	private final double fidelity;

	DetailLevel(double fidelity) {
		this.fidelity = fidelity;
	}

	/** How much of the file's usefulness this level delivers, 0..1. A tunable judgement, not a measurement. */
	public double fidelity() {
		return fidelity;
	}

	public static DetailLevel code(int chunks) {
		return switch (chunks) {
			case 1 -> CODE_1;
			case 2 -> CODE_2;
			default -> CODE_3;
		};
	}
}
