# MilalaApp Avisos (Android)

App pequeña para Android que lee los avisos (notificaciones) de compra de las apps elegidas
(por ejemplo MACH o la Billetera de Google) y los manda a MilalaApp, donde se anotan solos como gastos.

- Solo lee las apps que la persona marca. Los demás avisos nunca salen del teléfono.
- Se conecta con la dirección personal de MilalaApp (Ajustes › Compras solas › «Conectar este teléfono»).
- Si no hay internet, los avisos esperan en el teléfono y se mandan solos después.

## Cómo se arma

GitHub la arma sola (pestaña **Actions**) cada vez que cambia el código, y la publica en **Releases**
como `MilalaAvisos.apk`. Necesita dos secretos en *Settings › Secrets and variables › Actions*:

- `LLAVE_APK`: la llave de firma (texto en base64).
- `CLAVE_APK`: su contraseña.

Sin la misma llave, el teléfono no acepta las actualizaciones: guárdala en un lugar seguro.
