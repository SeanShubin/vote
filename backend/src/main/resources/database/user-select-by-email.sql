SELECT name, email, salt, hash, role
FROM users
WHERE email = ?
