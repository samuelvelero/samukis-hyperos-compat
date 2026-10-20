# Firma de la app

Aquí vive `release.jks` y `keystore/keystore.properties` (ambos ignorados por git).
Guarda una copia de los dos fuera de esta carpeta: si se pierden, Android no aceptará
actualizar la app instalada y habrá que desinstalarla.

En GitHub Actions los mismos datos están como secretos:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
