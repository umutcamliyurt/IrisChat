package com.umut.irischat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Server {

    private static final int    MAX_HOST_LENGTH    = 253;
    private static final int    MAX_CHANNEL_LENGTH = 50;
    private static final int    MAX_NICK_LENGTH    = 50;
    private static final int    MAX_NAME_LENGTH    = 100;
    private static final java.util.regex.Pattern SAFE_HOST =
            java.util.regex.Pattern.compile(
                    "^[A-Za-z0-9.\\-]+(:[0-9]{1,5})?$");

    private String       name;
    private String       host;
    private int          port;
    private String       nickname;
    private List<String> channels;
    private String       password;

    private boolean      tls;
    private String       saslLogin;

    private String       saslPassword;

    public Server(String name, String host, int port,
                  String nickname, List<String> channels,
                  String password, boolean tls,
                  String saslLogin, String saslPassword) {
        this.name         = name;
        this.host         = host;
        this.port         = port;
        this.nickname     = nickname;
        this.channels     = channels != null && !channels.isEmpty() ? channels : new ArrayList<>();
        this.password     = password     == null ? "" : password;
        this.tls          = tls;
        this.saslLogin    = saslLogin    == null ? "" : saslLogin;
        this.saslPassword = saslPassword == null ? "" : saslPassword;
    }

    public String       getName()        { return name; }
    public String       getHost()        { return host; }
    public int          getPort()        { return port; }
    public String       getNickname()    { return nickname; }
    public List<String> getChannels()    { return channels; }
    public String       getPassword()    { return password; }
    public boolean      isTls()          { return tls; }
    public String       getSaslLogin()   { return saslLogin; }
    public String       getSaslPassword(){ return saslPassword; }
    public boolean      hasPassword()    { return password     != null && !password.isEmpty(); }
    public boolean      hasSasl()        { return saslLogin    != null && !saslLogin.isEmpty()
            && saslPassword != null && !saslPassword.isEmpty(); }

    public void setName(String n)            { this.name = n; }
    public void setHost(String h)            { this.host = h; }
    public void setPort(int p)               { this.port = p; }
    public void setNickname(String n)        { this.nickname = n; }
    public void setChannels(List<String> c)  { this.channels = c; }
    public void setPassword(String p)        { this.password = p == null ? "" : p; }
    public void setTls(boolean t)            { this.tls = t; }
    public void setSaslLogin(String s)       { this.saslLogin = s == null ? "" : s; }
    public void setSaslPassword(String s)    { this.saslPassword = s == null ? "" : s; }

    public String getChannelsAsString() { return String.join(", ", channels); }

    public static List<String> parseChannels(String raw) {
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String ch = part.trim();
            if (!ch.isEmpty()) {
                if (!ch.startsWith("#") && !ch.startsWith("&")) ch = "#" + ch;
                if (ch.length() <= MAX_CHANNEL_LENGTH) {
                    list.add(ch);
                }
            }
        }
        return list;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name",         name);
        o.put("host",         host);
        o.put("port",         port);
        o.put("nickname",     nickname);
        o.put("password",     password);
        o.put("tls",          tls);
        o.put("saslLogin",    saslLogin);
        o.put("saslPassword", saslPassword);
        JSONArray arr = new JSONArray();
        for (String ch : channels) arr.put(ch);
        o.put("channels", arr);
        return o;
    }

    public static Server fromJson(JSONObject o) throws JSONException {
        String name     = o.getString("name");
        String host     = o.getString("host");
        String nickname = o.getString("nickname");

        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new JSONException("Invalid server name length");
        }
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            throw new JSONException("Invalid host length");
        }
        String hostOnly;
        if (host.startsWith("[")) {
            int closeBracket = host.indexOf(']');
            if (closeBracket < 0) throw new JSONException("Malformed IPv6 host: " + host);
            hostOnly = host.substring(1, closeBracket);
        } else {
            hostOnly = host.contains(":") ? host.substring(0, host.lastIndexOf(':')) : host;
        }
        if (!SAFE_HOST.matcher(hostOnly).matches()) {
            throw new JSONException("Invalid host characters: " + host);
        }
        if (nickname.isEmpty() || nickname.length() > MAX_NICK_LENGTH) {
            throw new JSONException("Invalid nickname length");
        }

        assertNoControlChars("name",     name);
        assertNoControlChars("host",     host);
        assertNoControlChars("nickname", nickname);

        List<String> channels = new ArrayList<>();
        if (o.has("channels")) {
            JSONArray arr = o.getJSONArray("channels");
            for (int i = 0; i < arr.length(); i++) {
                String ch = arr.getString(i);
                if (ch.length() <= MAX_CHANNEL_LENGTH) {
                    assertNoControlChars("channel", ch);
                    channels.add(ch);
                }
            }
        } else {
            channels.add(o.optString("channel", "#general"));
        }
        int port = o.getInt("port");
        if (port < 1 || port > 65535) {
            throw new JSONException("Invalid port: " + port);
        }
        return new Server(
                name,
                host,
                port,
                nickname,
                channels,
                o.optString("password",     ""),
                o.optBoolean("tls",         true),
                o.optString("saslLogin",    ""),
                o.optString("saslPassword", "")
        );
    }

    private static void assertNoControlChars(String field, String value) throws JSONException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new JSONException("Control character in field '" + field + "'");
            }
        }
    }

    @Override
    public String toString() {
        return name + " (" + (tls ? "ircs" : "irc") + "://" + host + ":" + port + ")";
    }
}
