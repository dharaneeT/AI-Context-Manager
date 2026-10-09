package com.contextlayer.backend.symbols;

public enum SymbolKind {
	CLASS,
	INTERFACE,
	ENUM,
	RECORD,
	TYPE,
	METHOD,
	CONSTRUCTOR,
	FUNCTION;

	public boolean isType() {
		return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == TYPE;
	}
}
