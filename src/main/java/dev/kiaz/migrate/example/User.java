package dev.kiaz.migrate.example;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String username;

    @Column
    private String email;

    protected User() {}
}
