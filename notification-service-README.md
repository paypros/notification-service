# notification-service

Microservicio consumidor de eventos. Escucha la cola SQS `paypro-notifications` y procesa eventos de pagos para enviar notificaciones (simuladas via log) al cliente o merchant.

## Stack
- Java 17 / Spring Boot 3.x
- AWS SQS (consumo de eventos)
- AWS SDK v2 (`software.amazon.awssdk:sqs`)
- AWS ECS Fargate / ECR Public
- GitHub Actions (CI/CD)

## Comportamiento
Cada 5 segundos hace long polling a SQS (espera hasta 5 segundos por mensajes). Al recibir un evento lo procesa y lo elimina de la cola. Si el procesamiento falla el mensaje permanece en la cola y se reintenta.

```
SQS paypro-notifications
    │
    └── @Scheduled(fixedDelay=5000)
            │
            ├── receiveMessage (max 10 mensajes, waitTime 5s)
            ├── procesa cada mensaje → log de notificación
            └── deleteMessage (elimina de la cola)
```

## Evento consumido
```json
{
  "paymentId": 1,
  "merchant": "Tienda Test",
  "amount": 100.00,
  "currency": "MXN",
  "status": "APPROVED"
}
```

## Log generado
```
NOTIFICATION — paymentId=1 merchant=Tienda Test amount=100.00 MXN status=APPROVED
```

## Variables de entorno
```
SQS_QUEUE_URL   → URL completa de la cola SQS
AWS_REGION      → región de AWS (default: us-east-1)
```

## Comunicación asíncrona vs síncrona
A diferencia de `provider-service` y `account-service` que son llamados síncronamente por `payment-service`, este servicio es completamente desacoplado:
- `payment-service` publica el evento y **no espera respuesta**
- Si `notification-service` está caído, los mensajes se acumulan en SQS y se procesan cuando vuelve
- El resultado del pago no depende de si la notificación se envió

---

## Retos encontrados y soluciones

### 1. Sin permisos para acceder a SQS en ECS
**Problema:** En local el SDK usa las credenciales del AWS CLI. En ECS no hay AWS CLI — el contenedor no tenía credenciales y el consumer fallaba con `Unable to load credentials`.
**Solución:** Diferencia importante entre dos roles en ECS:
- `executionRoleArn` → usado por ECS para operar infraestructura (pull imagen, escribir logs)
- `taskRoleArn` → usado por la **aplicación** para acceder a servicios AWS (SQS, S3, DynamoDB)

Se creó `ecsTaskRole` con política `AmazonSQSFullAccess` y se asignó como `taskRoleArn` en la Task Definition.

### 2. No necesita Cloud Map
**Decisión de diseño:** A diferencia de los otros servicios, `notification-service` no necesita registrarse en Cloud Map porque nadie lo llama directamente — él llama a SQS, no al revés. Esto simplifica su configuración.

### 3. `spring.profiles.active` en archivo de perfil
**Problema:** Poner `spring.profiles.active=dev` dentro de `application-dev.properties` causaba error:
```
Property 'spring.profiles.active' imported from location 'application-dev.properties' is invalid in a profile specific resource
```
**Solución:** `spring.profiles.active` solo puede ir en `application.properties`, nunca en archivos de perfil específico.

### 4. Dockerfile con nombre de artifact incorrecto
**Problema:** El Dockerfile tenía `COPY --from=build /app/target/account-service-*.jar app.jar` — copiado de otro servicio sin cambiar el nombre. La imagen arrancaba con error `Unable to access jarfile app.jar`.
**Solución:** Cambiado a `COPY --from=build /app/target/*.jar app.jar` usando wildcard genérico para evitar este tipo de errores en el futuro.

### 5. Mensajes consumidos antes de ver los logs
**Problema:** Al arrancar el servicio consumía mensajes acumulados en la cola tan rápido que parecía que no funcionaba — los mensajes desaparecían de la cola sin ver logs.
**Explicación:** El consumer funcionaba correctamente, solo que los mensajes se procesaban en millisegundos. Para verificar se usó `--follow` en `aws logs tail` para ver los logs en tiempo real mientras se enviaban nuevos pagos.

### 6. Errores de conexión a SQS en local
**Problema:** Al correr localmente, el `@Scheduled` intentaba conectarse a SQS cada 5 segundos. Si no había red o las credenciales expiraban llenaba los logs de errores.
**Solución:** Se envolvió todo el método `consume()` en un try-catch con `log.warn` en lugar de `log.error` para indicar que es un estado temporal esperado y no un error crítico.
