# Samukis HyperOS Compatibility Layer

Lleva eventos de apps de terceros a la **Hyper Island** de HyperOS 3 (Xiaomi, Redmi, POCO).

## Apps soportadas

| App | Qué muestra la isla | Estado |
|---|---|---|
| Discord | Canal de voz, cronómetro, silenciar / reactivar, desconectar | Probado en dispositivo |
| Uber | Una sola isla por viaje, solo desde la notificación en vivo. Pastilla: PIN + minutos a la recogida, PIN + "Llegó" (se abre sola), hora de llegada durante el viaje. Tarjeta: titular, vehículo, PIN, barra con el coche, llamar y compartir | Primera ronda en dispositivo; afinando |
| Google Meet | Reunión en curso con cronómetro, silenciar / cámara, salir; aviso "empieza en N min" con Unirse | Heurístico |
| WhatsApp (y Business) | Llamadas entrantes y en curso; grabación de nota de voz con cancelar | Heurístico |
| Drive, Google Fotos, Xiaomi Cloud, Play Store, Instagram | Subidas y descargas: anillo de porcentaje y tiempo restante | Heurístico |
| PedidosYa, Rappi | Estado del pedido con ETA, se descarta solo a los minutos | Sin probar aún |

Cada app es un traductor pequeño en `bridge/`. Las palabras clave que reconocen botones y
estados están en `res/values/arrays.xml`, en español e inglés.

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

## Calidad de vida

- **Mosaico de ajustes rápidos "Islas Samukis"**: pausa o reanuda todas las islas con un toque.
- **Respetar No molestar**: con No molestar activo solo las llamadas generan isla (opcional).
- **Arranque y actualizaciones**: un receptor re-vincula el servicio tras reiniciar o actualizar por Obtainium.
- **Inicio automático y batería**: la pantalla principal comprueba el permiso de inicio automático de
  HyperOS y la optimización de batería, y abre esas páginas de ajustes directamente.
- **Acceso directo al inspector** desde el icono de la app, y botón Compartir en el inspector.

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
