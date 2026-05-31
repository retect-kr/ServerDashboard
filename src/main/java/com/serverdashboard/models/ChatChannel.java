package com.serverdashboard.models;

public class ChatChannel {
    private String  id;
    private String  name;
    private String  alias;
    private String  color;
    private int     distance;   // 0 = global, >0 = block radius
    private String  permission; // null = everyone
    private boolean def;        // default channel for players

    public ChatChannel() {}

    public ChatChannel(String id, String name, String alias, String color,
                       int distance, String permission, boolean def) {
        this.id         = id;
        this.name       = name;
        this.alias      = alias;
        this.color      = color;
        this.distance   = distance;
        this.permission = permission;
        this.def        = def;
    }

    public String  getId()          { return id; }
    public String  getName()        { return name; }
    public String  getAlias()       { return alias; }
    public String  getColor()       { return color; }
    public int     getDistance()    { return distance; }
    public String  getPermission()  { return permission; }
    public boolean isDefault()      { return def; }

    public void setId(String id)               { this.id         = id; }
    public void setName(String name)           { this.name       = name; }
    public void setAlias(String alias)         { this.alias      = alias; }
    public void setColor(String color)         { this.color      = color; }
    public void setDistance(int distance)      { this.distance   = distance; }
    public void setPermission(String perm)     { this.permission = perm; }
    public void setDefault(boolean def)        { this.def        = def; }
}
