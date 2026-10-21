package com.hypeman.user.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** An Apple or Google account a user signs in with. */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private IdentityProvider provider;

    private String subject;

    private String email;

    /** Created with UserIdentityRepository.insertIfAbsent, so two first sign-ins can't collide. */
    protected UserIdentity() {
    }

    public UUID getUserId() {
        return userId;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    /** Apple sends the email only on the first sign-in, so a later sign-in without one keeps what we have. */
    public void updateEmail(String newEmail) {
        if (newEmail != null) {
            email = newEmail;
        }
    }
}
