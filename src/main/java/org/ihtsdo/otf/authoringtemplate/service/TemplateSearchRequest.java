package org.ihtsdo.otf.authoringtemplate.service;

public class TemplateSearchRequest {

	private Boolean logicalMatch;
	private Boolean lexicalMatch;
	private boolean preciseLexicalMatch = false;
	private boolean stated = true;

	public TemplateSearchRequest(Boolean logicalMatch, Boolean lexicalMatch, Boolean preciseLexicalMatch,
			boolean stated) {
		this.logicalMatch = logicalMatch;
		this.lexicalMatch = lexicalMatch;
		this.preciseLexicalMatch = preciseLexicalMatch;
		this.stated = stated;
	}

	public TemplateSearchRequest(Boolean logicalMatch, Boolean lexicalMatch) {
		this(logicalMatch, lexicalMatch, false, true);
	}

	public Boolean isLogicalMatch() {
		return logicalMatch;
	}

	public Boolean isLexicalMatch() {
		return lexicalMatch;
	}

	public Boolean isPreciseLexicalMatch() {
		return preciseLexicalMatch;
	}

	public boolean isStated() {
		return stated;
	}
}
