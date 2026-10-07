package com.t.tcine.domain.member.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String username;

    /** BCrypt 해시 */
    @Column(nullable = false)
    private String password;

    public Member(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
