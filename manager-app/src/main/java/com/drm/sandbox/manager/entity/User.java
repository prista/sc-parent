package com.drm.sandbox.manager.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_user", schema = "user_management")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "c_username")
    private String username;

    @Column(name = "c_password")
    private String password;

    @ManyToMany
    @JoinTable(
            // The join table that links users to their authorities (the many-to-many bridge).
            name = "t_user_2_authority",
            // Database schema where the join table lives (matches the SQL migration).
            schema = "user_management",
            // Foreign key in the join table pointing back to the owning entity (this User).
            joinColumns = @JoinColumn(name = "id_user"),
            // Foreign key in the join table pointing to the other side of the relation (Authority).
            inverseJoinColumns = @JoinColumn(name = "id_authority")
    )
    private List<Authority> authorities;
}
