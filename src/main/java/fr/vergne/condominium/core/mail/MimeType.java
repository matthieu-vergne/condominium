package fr.vergne.condominium.core.mail;

import java.util.Objects;

public class MimeType {
	private final String type;
	private final String subtype;

	MimeType(String type, String subtype) {
		this.type = type;
		this.subtype = subtype;
	}

	public String id() {
		return type + "/" + subtype;
	}

	@Override
	public final boolean equals(Object obj) {
		return obj instanceof MimeType that //
				&& Objects.equals(this.type, that.type)//
				&& Objects.equals(this.subtype, that.subtype);
	}

	@Override
	public final int hashCode() {
		return Objects.hash(type, subtype);
	}

	@Override
	public String toString() {
		return id();
	}

	public static class Application extends MimeType {
		Application(String subtype) {
			super("application", subtype);
		}

		public static final MimeType ICS = new Application("ics");
		public static final MimeType OCTET_STREAM = new Application("octet-stream");
		public static final MimeType PDF = new Application("pdf");
		public static final MimeType SPREADSHEET = new Application(
				"vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		public static final MimeType WORD = new Application(
				"vnd.openxmlformats-officedocument.wordprocessingml.document");
	}

	public static class Image extends MimeType {
		Image(String subtype) {
			super("image", subtype);
		}

		public static final MimeType GIF = new Image("gif");
		public static final MimeType HEIC = new Image("heic");
		public static final MimeType JPEG = new Image("jpeg");
		public static final MimeType PNG = new Image("png");
	}

	public static class Message extends MimeType {
		Message(String subtype) {
			super("message", subtype);
		}

		public static final MimeType DELIVERY_STATUS = new Message("delivery-status");
		public static final MimeType RFC822 = new Message("rfc822");
	}

	public static class Multipart extends MimeType {
		Multipart(String subtype) {
			super("multipart", subtype);
		}

		public static final MimeType ALTERNATIVE = new Multipart("alternative");
		public static final MimeType MIXED = new Multipart("mixed");
		public static final MimeType RELATED = new Multipart("related");
		public static final MimeType REPORT = new Multipart("report");
	}

	public static class Text extends MimeType {
		Text(String subtype) {
			super("text", subtype);
		}

		public static final MimeType AMP = new Text("x-amp-html");
		public static final MimeType CALENDAR = new Text("calendar");
		public static final MimeType HTML = new Text("html");
		public static final MimeType PLAIN = new Text("plain");
		public static final MimeType RFC822_HEADERS = new Text("rfc822-headers");
	}

	public static class Video extends MimeType {
		Video(String subtype) {
			super("video", subtype);
		}

		public static final MimeType MP4 = new Video("mp4");
	}

	public static MimeType parse(String id) {
		String[] split = id.split("/");
		if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
			throw new IllegalArgumentException("Invalid MIME type: " + id);
		}
		return new MimeType(split[0], split[1]);
	}
}
