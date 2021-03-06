package org.zstack.header.image;

import org.springframework.http.HttpMethod;
import org.zstack.header.identity.Action;
import org.zstack.header.message.*;
import org.zstack.header.notification.ApiNotification;
import org.zstack.header.other.APIAuditor;
import org.zstack.header.rest.RestRequest;
import org.zstack.header.storage.backup.BackupStorageVO;
import org.zstack.header.volume.VolumeVO;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 */
@Action(category = ImageConstant.ACTION_CATEGORY)
@RestRequest(
        path = "/images/data-volume-templates/from/volumes/{volumeUuid}",
        method = HttpMethod.POST,
        parameterName = "params",
        responseClass = APICreateDataVolumeTemplateFromVolumeEvent.class
)
@DefaultTimeout(timeunit = TimeUnit.HOURS, value = 72)
public class APICreateDataVolumeTemplateFromVolumeMsg extends APICreateMessage implements APIAuditor {
    @APIParam(maxLength = 255)
    private String name;
    @APIParam(required = false, maxLength = 2048)
    private String description;
    @APIParam(resourceType = VolumeVO.class, checkAccount = true, operationTarget = true)
    private String volumeUuid;
    @APIParam(required = false, nonempty = true, resourceType = BackupStorageVO.class)
    private List<String> backupStorageUuids;

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

    public List<String> getBackupStorageUuids() {
        return backupStorageUuids;
    }

    public void setBackupStorageUuids(List<String> backupStorageUuids) {
        this.backupStorageUuids = backupStorageUuids;
    }

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }
 
    public static APICreateDataVolumeTemplateFromVolumeMsg __example__() {
        APICreateDataVolumeTemplateFromVolumeMsg msg = new APICreateDataVolumeTemplateFromVolumeMsg();

        msg.setBackupStorageUuids(Collections.singletonList(uuid()));
        msg.setName("My Data Volume Template");
        msg.setVolumeUuid(uuid());

        return msg;
    }

    public ApiNotification __notification__() {
        APIMessage that = this;

        return new ApiNotification() {
            @Override
            public void after(APIEvent evt) {
                if (evt.isSuccess()) {
                    ntfy("Created from a data volume[uuid:%s]", volumeUuid)
                            .resource(((APICreateDataVolumeTemplateFromVolumeEvent) evt).getInventory().getUuid(),
                                    ImageVO.class.getSimpleName())
                            .messageAndEvent(that, evt).done();
                }
            }
        };
    }

    @Override
    public Result audit(APIMessage msg, APIEvent rsp) {
        return new Result(rsp.isSuccess() ? ((APICreateDataVolumeTemplateFromVolumeEvent)rsp).getInventory().getUuid() : "", ImageVO.class);
    }
}
