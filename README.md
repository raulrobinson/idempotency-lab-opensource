# Laboratorio Basico de Idempotencia

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?style=for-the-badge&logo=springboot)
![Spring WebFlux](https://img.shields.io/badge/Spring%20WebFlux-Reactive-6DB33F?style=for-the-badge&logo=spring)
![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud-Gateway-6DB33F?style=for-the-badge&logo=spring)
![Node.js](https://img.shields.io/badge/Node.js-22-339933?style=for-the-badge&logo=nodedotjs)
![MongoDB](https://img.shields.io/badge/MongoDB-7-47A248?style=for-the-badge&logo=mongodb)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker)
![Idempotency](https://img.shields.io/badge/Pattern-Idempotency-blueviolet?style=for-the-badge)
![Reactive](https://img.shields.io/badge/Architecture-Reactive-blue?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)

### Introducción:
Este laboratorio implementa un patrón de idempotencia independiente del dominio. 
1. El cliente envía una petición HTTP con un Idempotency-Key; 
2. API Gateway la enruta hacia un servicio WebFlux encargado de controlar si la operación debe ejecutarse, bloquearse o responderse desde una respuesta previamente almacenada.
3. El servicio de idempotencia válida la clave, calcula un hash del payload y consulta MongoDB para identificar el estado de la petición. 
4. Si la clave no existe, registra la operación como PROCESSING, invoca el servicio de dominio implementado en Node.js y guarda la respuesta final como COMPLETED. 
5. Si la misma petición se repite con la misma clave y el mismo payload, el sistema no vuelve a ejecutar el dominio, sino que retorna la respuesta cacheada.
6. Si la clave se reutiliza con un payload diferente, el sistema responde con error 422. 
7. Si otra petición con la misma clave aún está en proceso, responde con 409. 
8. De esta forma, el componente evita duplicidad de operaciones, mantiene consistencia y puede reutilizarse para distintos casos de negocio sin depender de la lógica interna del dominio.

---

- Diagrama General:

```
         Cliente
            ↓
    Spring Cloud Gateway
            ↓
Servicio de Idempotencia
    ↓               ↓
 MongoDB      Lambda Node.js
```

---

- Diagrama de Flujo:
```mermaid
flowchart LR
    CLIENT[Cliente]

    GATEWAY[Spring Cloud Gateway<br/>API Gateway]

    IDEM[Spring WebFlux<br/>Idempotency Service]

    MONGO[(MongoDB<br/>Idempotency Store)]

    DOMAIN[Lambda Node.js<br/>Servicio de Dominio]

    CLIENT --> GATEWAY
    GATEWAY --> IDEM

    IDEM -->|consulta / guarda estado| MONGO
    IDEM -->|solo si es nueva petición| DOMAIN

    DOMAIN --> IDEM
    IDEM --> GATEWAY
    GATEWAY --> CLIENT
``` 

---

- Diagrama de Secuencia:

```mermaid
sequenceDiagram
    autonumber

    actor Client as Cliente / Postman
    participant GW as Spring Cloud Gateway<br/>:8080
    participant API as Idempotency Controller<br/>Spring WebFlux :8081
    participant IDEM as Idempotency Service
    participant MONGO as MongoDB<br/>idempotency_records
    participant NODE as Lambda Node.js<br/>Domain Handler :3001

    Client->>GW: POST /api/execute<br/>Idempotency-Key: order-001<br/>JSON Payload
    GW->>GW: RewritePath<br/>/api/execute -> /idempotency/execute
    GW->>API: POST /idempotency/execute<br/>Idempotency-Key + Payload

    API->>IDEM: handle(idempotencyKey, payload)

    alt Idempotency-Key ausente o vacía
        IDEM-->>API: 400 Bad Request<br/>Idempotency-Key header is required
        API-->>GW: 400
        GW-->>Client: 400 Bad Request
    else Idempotency-Key presente
        IDEM->>IDEM: Generate SHA-256 requestHash(payload)
        IDEM->>MONGO: findById(idempotencyKey)

        alt No existe registro
            MONGO-->>IDEM: empty

            IDEM->>MONGO: insert PROCESSING<br/>_id = idempotencyKey<br/>requestHash<br/>status = PROCESSING<br/>expiresAt

            alt Insert exitoso / lock adquirido
                MONGO-->>IDEM: PROCESSING saved

                IDEM->>NODE: POST /invoke<br/>payload original

                alt Dominio responde OK
                    NODE-->>IDEM: 200 OK<br/>domainResponse

                    IDEM->>MONGO: update status COMPLETED<br/>responseStatus = 200<br/>responseBody = domainResponse
                    MONGO-->>IDEM: COMPLETED saved

                    IDEM-->>API: 200 OK<br/>domainResponse
                    API-->>GW: 200 OK
                    GW-->>Client: 200 OK<br/>respuesta original
                else Dominio responde error
                    NODE-->>IDEM: 500 Error

                    IDEM->>MONGO: update status FAILED<br/>errorMessage
                    MONGO-->>IDEM: FAILED saved

                    IDEM-->>API: 500 Internal Server Error<br/>Domain execution failed
                    API-->>GW: 500
                    GW-->>Client: 500 Internal Server Error
                end

            else DuplicateKeyException / otro request ganó el lock
                MONGO-->>IDEM: duplicate key
                IDEM-->>API: 409 Conflict<br/>Request is already processing
                API-->>GW: 409
                GW-->>Client: 409 Conflict
            end

        else Existe registro
            MONGO-->>IDEM: existing record

            IDEM->>IDEM: compare existing.requestHash<br/>vs current requestHash

            alt Misma key pero payload diferente
                IDEM-->>API: 422 Unprocessable Entity<br/>Idempotency-Key reused with different payload
                API-->>GW: 422
                GW-->>Client: 422 Unprocessable Entity

            else Payload igual y status COMPLETED
                IDEM-->>API: cached response<br/>status + responseBody
                API-->>GW: respuesta cacheada
                GW-->>Client: misma respuesta original

            else Payload igual y status PROCESSING
                IDEM-->>API: 409 Conflict<br/>Request is already processing
                API-->>GW: 409
                GW-->>Client: 409 Conflict

            else Payload igual y status FAILED
                IDEM-->>API: 500 Internal Server Error<br/>Previous request failed
                API-->>GW: 500
                GW-->>Client: 500 Internal Server Error
            end
        end
    end
```

---

- Diagrama de Componentes:

```mermaid
flowchart TB
    CLIENT[Cliente / Postman]

    subgraph LAB[Idempotency Lab Local]

        subgraph GATEWAY[Gateway Service<br/>Spring Cloud Gateway :8080]
            ROUTES[Route Locator<br/>/api/**]
            REWRITE[RewritePath Filter<br/>/api/execute -> /idempotency/execute]
            PROXY[Reactive Proxy]
        end

        subgraph IDEM[Idempotency Service<br/>Spring Boot WebFlux :8081]
            CONTROLLER[IdempotencyController<br/>POST /idempotency/execute]
            SERVICE[IdempotencyService<br/>Orquestador]
            HASHER[Request Hasher<br/>SHA-256 payload]
            POLICY[Policy Handler<br/>TTL / estados / errores]
            WEBCLIENT[WebClient<br/>HTTP client reactivo]
            REPOSITORY[Reactive Mongo Repository]
        end

        subgraph DOMAIN[Lambda Node Domain<br/>Node.js :3001]
            EXPRESS[Express Adapter<br/>POST /invoke]
            HANDLER[Lambda Handler<br/>business simulation]
            ERROR_SIM[Forced Error Simulator<br/>forceError=true]
        end

        subgraph DATA[MongoDB :27017]
            IDEM_COLLECTION[(idempotency_records)]
            DLQ_COLLECTION[(failed_events opcional)]
        end
    end

    CLIENT -->|POST /api/execute<br/>Idempotency-Key + JSON| ROUTES
    ROUTES --> REWRITE
    REWRITE --> PROXY
    PROXY --> CONTROLLER

    CONTROLLER --> SERVICE
    SERVICE --> HASHER
    SERVICE --> POLICY
    SERVICE --> REPOSITORY

    REPOSITORY -->|findById / save / update| IDEM_COLLECTION

    SERVICE -->|solo si request nuevo| WEBCLIENT
    WEBCLIENT --> EXPRESS
    EXPRESS --> HANDLER
    HANDLER --> ERROR_SIM

    HANDLER -->|domain response| EXPRESS
    EXPRESS --> WEBCLIENT
    WEBCLIENT --> SERVICE

    SERVICE -->|save COMPLETED / FAILED| REPOSITORY
    SERVICE -->|response cacheada o nueva| CONTROLLER
    CONTROLLER --> PROXY
    PROXY --> CLIENT

    POLICY -.errores controlados.-> DLQ_COLLECTION
```

---

### Licencia:
Este proyecto está bajo la Licencia MIT. Consulta el archivo LICENSE para más detalles.

### Autor:
- [Raul R. Bolivar Navas](https://github.com/raulrobinson/idempotency-lab-opensource)
