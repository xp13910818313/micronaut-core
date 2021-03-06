/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client.aop

import io.micronaut.context.ApplicationContext
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * @author graemerocher
 * @since 1.0
 */
class HeaderSpec extends Specification {
    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = context.getBean(EmbeddedServer).start()

    void "test send and receive header"() {
        given:
        UserClient userClient = context.getBean(UserClient)
        User user = userClient.get("Fred")

        expect:
        user.username == "Fred"
        user.age == 10

    }

    @Client('/headers')
    static interface UserClient extends MyApi {

    }

    @Controller('/headers')
    static class UserController implements MyApi {

        @Override
        User get(@Header('X-Username') String username) {
            return new User(username:username, age: 10)
        }
    }

    static interface MyApi {

        @Get('/user')
        User get(@Header('X-Username') String username)
    }

    static class User {
        String username
        Integer age
    }

}
