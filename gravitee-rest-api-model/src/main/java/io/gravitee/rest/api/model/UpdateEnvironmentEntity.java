/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.model;

import java.util.List;
import java.util.Objects;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
public class UpdateEnvironmentEntity {

    private List<String> hrids;

    @NotNull
    @Size(min = 1)
    private String name;

    private String description;

    private List<String> domainRestrictions;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getDomainRestrictions() {
        return domainRestrictions;
    }

    public void setDomainRestrictions(List<String> domainRestrictions) {
        this.domainRestrictions = domainRestrictions;
    }

    public List<String> getHrids() {
        return hrids;
    }

    public void setHrids(List<String> hrids) {
        this.hrids = hrids;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateEnvironmentEntity that = (UpdateEnvironmentEntity) o;
        return (
            Objects.equals(hrids, that.hrids) &&
            Objects.equals(name, that.name) &&
            Objects.equals(description, that.description) &&
            Objects.equals(domainRestrictions, that.domainRestrictions)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(hrids, name, description, domainRestrictions);
    }

    @Override
    public String toString() {
        return (
            "UpdateEnvironmentEntity{" +
            "hrids=" +
            hrids +
            ", name='" +
            name +
            '\'' +
            ", description='" +
            description +
            '\'' +
            ", domainRestrictions=" +
            domainRestrictions +
            '}'
        );
    }
}
