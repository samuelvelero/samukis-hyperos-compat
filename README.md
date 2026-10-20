# Samukis HyperOS Compatibility Layer

Lleva eventos de apps de terceros a la **Hyper Island** de HyperOS 3 (Xiaomi, Redmi, POCO).
Primera app soportada: **Discord**, llamadas entrantes y llamadas en curso.

- Sin servicio en primer plano, sin sondeo, sin wakelocks: un `NotificationListenerService`
  que el sistema despierta solo cuando Discord publica, actualiza o quita una notificación.
- Las islas se construyen con [HyperIsland ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)
  sobre la API oficial de notificaciones de foco de Xiaomi.
- Interfaz en español e inglés.

## Instalar y actualizar con Obtainium

1. Instala [Obtainium](https://github.com/ImranR98/Obtainium/releases).
2. Abre este enlace en el teléfono, o pégalo en Obtainium → **Agregar app**:

   ```
   https://github.com/samuelvelero/samukis-hyperos-compat
   ```

   Enlace directo: `obtainium://add/https://github.com/samuelvelero/samukis-hyperos-compat`

3. Obtainium descargará el APK del último Release y avisará cuando haya versiones nuevas.

## Configuración en el teléfono

1. Abre la app y concede **Acceso a notificaciones**.
2. En **Permiso de notificaciones de foco → Abrir**, activa "Notificaciones de foco"
   (en algunas ROM aparece como "Mostrar en la barra de estado").
3. Pulsa **Probar isla** para comprobar que la pastilla aparece.
4. En Ajustes de batería de la app, elige **Sin restricciones** para que HyperOS no mate el servicio.

Si una llamada de Discord no aparece, activa el **Inspector de notificaciones**, repite la llamada
y copia el registro: contiene los campos exactos que manda Discord para ajustar la detección.

## Desarrollo

Requisitos: JDK 17 o superior y el SDK de Android (API 36).

```bash
./gradlew :app:assembleDebug
```

### Publicar una versión

```bash
git tag v1.0.1
git push origin v1.0.1
```

El workflow `Release` compila, firma y publica el APK en GitHub Releases. Los secretos
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD` deben existir en el repositorio.

### Agregar otra app

Implementa `AppBridge` (ver `bridge/discord/DiscordBridge.kt`), regístralo en `BridgeRegistry`
y añade su paquete al bloque `<queries>` del manifiesto.

## Licencia

MIT.
