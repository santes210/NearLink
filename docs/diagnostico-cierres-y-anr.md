# Diagnóstico: la app se cierra al enviar/recibir y el otro móvil muestra "NearLink no responde"

## Síntoma

- Al **enviar** o **recibir** un mensaje la app se cierra.
- En el móvil del receptor aparece el diálogo de ANR: *"NearLink no responde"*.

Ambos síntomas son **la misma causa**: el hilo principal (UI) se bloquea más de 5 segundos
justo en el momento en que la base de datos cambia por un mensaje. El sistema muestra el ANR y
la app muere (o el usuario la cierra desde el propio diálogo).

---

## Causa raíz 1 — Todo el descifrado se ejecutaba en el hilo principal

`viewModelScope` de AndroidX es:

```kotlin
CloseableCoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

Todos los ViewModels de NearLink recogen los flujos de Room así:

```kotlin
val messages = messageRepository.observeMessages(peerId)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

`stateIn` recoge en el contexto del scope → **`Dispatchers.Main.immediate`**. Y Room confina su
flujo a su propio ejecutor con `flowOn`, por lo que **todos los operadores posteriores
(`mapLatest`) corren en el hilo del colector: el principal**.

Y ahí dentro estaba el trabajo pesado. Antes del arreglo, `MessageRepositoryImpl` hacía:

```kotlin
messageDao.observeForPeer(peerId).mapLatest { entities ->
    entities.mapNotNull { entity -> entity.toDomainMessage(entity.peerId) }  // ← en Main
}
```

y `toDomainMessage` llamaba a `cipher.decrypt(...)`, que a su vez llama a `keysFor(peerId)`:

1. `peerRepository.find(peerId)` → salto a `Dispatchers.IO` + consulta SQL + Base64.
2. `identityRepository.sharedSecret(key)` → salto a `Dispatchers.Default` + `KeyFactory` + **ECDH
   P-256** + **HKDF-SHA256**.
3. `crypto.localKey()` → SHA-256 entrando en el monitor `@Synchronized` de `CryptoManager`.
4. `crypto.decrypt(...)` → AES-256-GCM.

**Eso se repetía para CADA mensaje de la conversación, en cada emisión, en el hilo principal.**

Y las emisiones son muchas: un solo mensaje enviado provoca al menos tres escrituras
(`upsert` al encolar, `updateStatus(SENT)`, `updateStatus(QUEUED)` si falla el ACK), más
`markConversationRead` al abrir el chat y los `upsert` de peers durante el handshake. Cada una
invalida el `Flow` de Room y obliga a repetir todo el ciclo.

El mismo patrón estaba en:

| Flujo | Trabajo pesado que corría en Main |
|---|---|
| `MessageRepositoryImpl.observeMessages` | ECDH + HKDF + AES-GCM **por mensaje** |
| `MessageRepositoryImpl.observeConversations` | lo anterior **por conversación** + una consulta SQL extra por fila (`peerRepository.find`) |
| `ChannelRepositoryImpl.observeMessages` | `dao.find(channelId)` + descifrado Keystore + HKDF **por mensaje** |
| `IdentityRepositoryImpl.observeIdentity` | lectura del fichero de identidad + Android Keystore + SHA-256 |
| `PeerRepositoryImpl.observePeers` | decodificado Base64 de la clave pública por peer |

## Causa raíz 2 — Una excepción del Keystore mataba el proceso

`toDomainMessage` sólo protegía el Base64:

```kotlin
val box = runCatching { SealedBox(...) }.getOrNull() ?: return null
val bytes = cipher.decrypt(peerId, box) ?: return null   // ← SIN proteger
```

`cipher.decrypt` → `keysFor` → `crypto.localKey()` → `ensureIdentity()` → Android Keystore, que
en dispositivos reales lanza `KeyStoreException`, `ProviderException`,
`UnrecoverableKeyException`, `IllegalStateException`… Esa excepción salía del flujo que
`stateIn(viewModelScope)` estaba recogiendo, llegaba al `SupervisorJob` del ViewModel y de ahí
al `UncaughtExceptionHandler` del hilo → **cierre de la app**.

> `SupervisorJob` **no** evita el crash: sólo impide que un hijo cancele a sus hermanos. La
> excepción sigue propagándose al manejador por defecto del hilo, que en Android mata el proceso.

## Causa raíz 3 — La notificación se publicaba en el hilo principal y sin red de seguridad

`AppContainer.notifyIncoming`:

```kotlin
appScope.launch(Dispatchers.Main.immediate) {
    NearLinkNotifications.showMessage(...)
}
```

Dos problemas:

1. `NotificationManagerCompat.notify` es una llamada **binder** lanzada en el hilo principal,
   sumándose al bloqueo de la causa raíz 1 justo cuando llegaba el mensaje. (Aquí es donde la
   intuición sobre el sistema de notificaciones era correcta.)
2. `appScope` era `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, **sin
   `CoroutineExceptionHandler`**. Cualquier excepción dentro de ese `launch` mataba el proceso.

Además se notificaba siempre, incluso con el chat abierto delante, con un canal
`IMPORTANCE_HIGH` (heads-up + sonido + vibración).

## Causa raíz 4 — Los mensajes se perdían en silencio

- `_incoming = MutableSharedFlow(extraBufferCapacity = 64)` con `tryEmit`: la política por
  defecto de `MutableSharedFlow` ya es `SUSPEND`, así que con el búfer lleno `tryEmit` devolvía
  `false` — y como nadie miraba el valor devuelto, **la trama se descartaba sin avisar**.
- `MeshInbox.start()` se llamaba **después** de `transport.start()`, dejando una ventana en la que
  el transporte ya emitía y todavía no había colector.
- `MeshInbox` envolvía todo en `runCatching { handle(envelope) }` sin registrar nada: un fallo de
  descifrado o de persistencia desaparecía sin dejar rastro.
- `GattServer.notify()` enviaba notificaciones GATT sin comprobar si el par había escrito el CCCD.
  La pila BLE las descarta en silencio y el emisor daba la trama por entregada (y luego esperaba
  8 s un ACK que nunca llegaba).

---

## Arreglos aplicados

| Fichero | Cambio |
|---|---|
| `MessageRepositoryImpl` | `flowOn(dispatchers.default)` en `observeMessages`/`observeConversations`. Las claves se derivan **una vez por emisión** (no por mensaje). Se elimina la consulta SQL redundante por conversación (los datos ya vienen del `JOIN` de la DAO). Un fallo de descifrado descarta sólo ese mensaje. |
| `ChannelRepositoryImpl` | `flowOn(dispatchers.default)`; la clave maestra del canal se resuelve una vez por emisión; `storedGroupKey` ya no propaga fallos del Keystore. |
| `IdentityRepositoryImpl` | `flowOn(dispatchers.io)` en `observeIdentity` (disco + Keystore fuera de Main). |
| `PeerRepositoryImpl` | `flowOn(dispatchers.io)` en `observePeers`. |
| `CryptoManager` | `localKey()` cacheada (era un SHA-256 + monitor por cada mensaje). Se invalida en `regenerate()`. |
| `AppContainer` | `appScope` con `CoroutineExceptionHandler`. La notificación se publica en `dispatchers.default` y **sólo si la app no está en primer plano**. |
| `NearLinkForegroundService` | Scope con `CoroutineExceptionHandler`. `meshInbox.start()` **antes** de `transport.start()`. La notificación del servicio usa `status.connectedPeers` en vez de releer todos los peers de la BD. |
| `NearLinkTransport` | `_incoming` con `onBufferOverflow = SUSPEND` y `emit()` en lugar de `tryEmit()`: se suspende en vez de descartar. |
| `GattServer` | `notify()` devuelve `false` si el par aún no ha habilitado las notificaciones (CCCD). |
| `MeshInbox` | Las tramas descartadas se registran en logcat. |

---

## Segunda tanda: enrutado por origen, grupos y el PIN muerto

### Los mensajes 1:1 reenviados por un repetidor ya se descifran

El buzón usaba `envelope.senderId`, que es la dirección del **salto inmediato**, no la del emisor
original. En un enlace directo A→B daba igual, pero en A→C→B el receptor probaba las claves de C,
fallaba y descartaba el mensaje en silencio.

La cabecera de trama ya llevaba el `originId` (prefijo de 6 bytes del SHA-256 de la clave pública
del emisor), pero no había forma de convertirlo en una dirección MAC — que es como se busca la
clave del par. Se ha añadido esa traducción:

- `IdentityRepository.nodeIdOf(publicKey)` calcula el nodeId de cualquier clave pública.
- `NearLinkTransport` mantiene un índice `nodeId → MAC` que **se reconstruye al arrancar** con las
  claves públicas ya guardadas (sobrevive a los reinicios de proceso) y **se actualiza en cada
  handshake**.
- `IncomingEnvelope` lleva ahora `originAddress`, ya resuelto por el transporte.
- `MeshInbox` usa el origen para el filtro de bloqueados, para descifrar y como `peerId` de la
  conversación: un mensaje reenviado aparece en el chat con su emisor real, no con el repetidor.
- El **ACK vuelve al emisor original** (`deliver(originAddress, ack)`, con respaldo al salto
  inmediato). Antes se quedaba en el repetidor y el emisor agotaba los 8 s de espera.

Cubierto por `app/src/test/java/.../OriginRoutingTest.kt`, que fija el contrato del campo de
origen de la cabecera (6 bytes, hex en mayúsculas, invariante bajo relay).

### Los grupos NO tenían este problema

Se ha verificado, no asumido: la clave de un canal se deriva del **código compartido**
(`GroupKeys.groupKey(code)`), no de un secreto por par. `ChannelRepositoryImpl.decryptIncoming`
resuelve la clave con `storedGroupKey(channelId)` y el AAD (`channelId + senderNodeId + nombre`)
viaja entero dentro de la trama, así que una trama reenviada por N saltos se descifra igual. Los
grupos ya funcionaban en malla.

### Eliminado el código de emparejamiento por PIN (estaba muerto)

Cadena completa que no llamaba nadie:

```
SettingsViewModel.rotatePin()        <- ninguna pantalla lo invoca
  -> RotatePairingPinUseCase
    -> SettingsRepository.rotatePairingPin()
      -> CryptoManager.randomPin()
IdentityRepository.derivePairingKey()  <- cero llamadas
  -> CryptoManager.derivePinKey()      <- PBKDF2-HMAC-SHA256 de 600.000 iteraciones
UserSettings.pairingPin / pinExpiresAt <- nunca se muestran
```

No había ni un solo `string` de PIN o emparejamiento en `strings.xml`. Se ha borrado todo
(`derivePinKey`, `randomPin`, `derivePairingKey`, `rotatePairingPin`, `RotatePairingPinUseCase`,
las dos claves de `settings` y los dos campos de `UserSettings`). No hace falta migración: la
tabla `settings` es clave/valor y las filas huérfanas se ignoran.

**Lo que empareja de verdad hoy:** el handshake BLE intercambia las claves públicas X.509,
`completeHandshake` deriva el secreto compartido por ECDH y guarda la huella. La verificación
fuera de banda es esa huella, que se muestra en Ajustes y en la cabecera del chat.

**Hueco que queda (menor, sin arreglar):** `Peer.verified` nunca se pone a `true`. `HomeScreen`
pasa `verified = true` a pelo para el chip de la identidad local, y `ChatScreen`/`PeerItem`
leen `peer.verified`, que siempre es `false`. El candado de "verificado" nunca se enciende:
haría falta una acción explícita de confirmar huella en persona.

## Cómo verificarlo en un dispositivo

1. `./gradlew :app:assembleDebug` e instalar en los dos móviles.
2. Activar **Opciones de desarrollador → Mostrar todos los ANR** y
   **Profileable/StrictMode** ya viene activo en debug.
3. Enviar 10 mensajes seguidos con el chat abierto: la interfaz no debe congelarse.
4. `adb logcat -s NearLink AndroidRuntime StrictMode` — con estos cambios, cualquier trama
   descartada o excepción de la malla aparece en el log en vez de cerrar la app.
5. `adb shell dumpsys gfxinfo com.nearlink.app.debug framestats` para confirmar que no quedan
   frames de cientos de ms al enviar/recibir.
