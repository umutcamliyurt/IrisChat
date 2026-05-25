package com.umut.irischat;

public class ChatMessage {
    public enum Type { SENT, RECEIVED, SYSTEM }

    private final String  nick;
    private final String  text;
    private final Type    type;
    private final String  replyToNick;
    private final String  replyToText;
    private final String  imageUrl;
    private final boolean encrypted;
    private final String  rawWire;
    private final long    timestamp;

    public ChatMessage(String nick, String text, Type type) {
        this(nick, text, type, null, null, null, false);
    }

    public ChatMessage(String nick, String text, Type type,
                       String replyToNick, String replyToText) {
        this(nick, text, type, replyToNick, replyToText, null, false);
    }

    public ChatMessage(String nick, String text, Type type,
                       String replyToNick, String replyToText, String imageUrl) {
        this(nick, text, type, replyToNick, replyToText, imageUrl, false);
    }

    public ChatMessage(String nick, String text, Type type,
                       String replyToNick, String replyToText, String imageUrl,
                       boolean encrypted) {
        this(nick, text, type, replyToNick, replyToText, imageUrl, encrypted, null);
    }

    public ChatMessage(String nick, String text, Type type,
                       String replyToNick, String replyToText, String imageUrl,
                       boolean encrypted, String rawWire) {
        this(nick, text, type, replyToNick, replyToText, imageUrl, encrypted, rawWire,
                System.currentTimeMillis());
    }

    public ChatMessage(String nick, String text, Type type,
                       String replyToNick, String replyToText, String imageUrl,
                       boolean encrypted, String rawWire, long timestamp) {
        this.nick        = nick;
        this.text        = text;
        this.type        = type;
        this.replyToNick = replyToNick;
        this.replyToText = replyToText != null && replyToText.length() > 80
                ? replyToText.substring(0, 80) + "…" : replyToText;
        this.imageUrl    = imageUrl;
        this.encrypted   = encrypted;
        this.rawWire     = rawWire;
        this.timestamp   = timestamp;
    }

    public String  getNick()        { return nick; }
    public String  getText()        { return text; }
    public Type    getType()        { return type; }
    public boolean isSent()         { return type == Type.SENT; }
    public boolean isSystem()       { return type == Type.SYSTEM; }
    public String  getReplyToNick() { return replyToNick; }
    public String  getReplyToText() { return replyToText; }
    public boolean hasReply()       { return replyToNick != null && replyToText != null; }
    public String  getImageUrl()    { return imageUrl; }
    public boolean isEncrypted()    { return encrypted; }
    public String  getRawWire()     { return rawWire; }
    public long    getTimestamp()   { return timestamp; }
}