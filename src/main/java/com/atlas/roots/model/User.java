package com.atlas.roots.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A registered user of the Roots system.
 *
 * <p>Implements {@link Auditable} but deliberately not {@link RootNode}:
 * a user is the <em>owner</em> of nodes, not a node itself. Keeping
 * users out of the polymorphic node hierarchy avoids the classic
 * "everything inherits from Entity" anti-pattern and keeps the
 * hierarchy semantically focused on the cognitive/financial domain.</p>
 */
public class User implements Auditable {

    public enum Role { ADMIN, VIEWER }

    private long          id;
    private String        username;
    private String        passwordHash;   // BCrypt
    private Role          role;
    private LocalDateTime createdAt;
    private LocalDateTime lastTouched;

    public User(String username, String passwordHash, Role role) {
        this.username     = Objects.requireNonNull(username);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role         = Objects.requireNonNull(role);
        this.createdAt    = LocalDateTime.now();
        this.lastTouched  = this.createdAt;
    }

    public User(long id, String username, String passwordHash, Role role,
                LocalDateTime createdAt) {
        this.id           = id;
        this.username     = Objects.requireNonNull(username);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role         = Objects.requireNonNull(role);
        this.createdAt    = Objects.requireNonNull(createdAt);
        this.lastTouched  = createdAt;
    }

    @Override public LocalDateTime getCreatedAt()   { return createdAt; }
    @Override public LocalDateTime getLastTouched() { return lastTouched; }
    @Override public void touch() { this.lastTouched = LocalDateTime.now(); }

    public long getId()                 { return id; }
    public void setId(long id)          { this.id = id; }

    public String getUsername()                      { return username; }
    public void   setUsername(String username)       { this.username = username; }

    public String getPasswordHash()                  { return passwordHash; }
    public void   setPasswordHash(String passwordHash){ this.passwordHash = passwordHash; }

    public Role getRole()              { return role; }
    public void setRole(Role role)     { this.role = role; }

    public boolean isAdmin() { return role == Role.ADMIN; }

    @Override
    public String toString() {
        return "User[%d, %s, %s]".formatted(id, username, role);
    }
}
