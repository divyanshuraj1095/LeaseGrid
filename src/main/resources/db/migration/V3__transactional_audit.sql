CREATE FUNCTION record_audit_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    snapshot jsonb := to_jsonb(NEW);
    event_name text := TG_OP;
    subject uuid;
    actor uuid := nullif(current_setting('leasegrid.actor',true),'')::uuid;
    reason text := nullif(current_setting('leasegrid.reason',true),'');
BEGIN
    IF TG_TABLE_NAME='resource_pools' AND TG_OP='UPDATE' AND
       (snapshot - 'allocated_capacity' - 'updated_at')=(to_jsonb(OLD) - 'allocated_capacity' - 'updated_at') THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='team_quotas' AND TG_OP='UPDATE' AND (snapshot->>'quota_limit')=(to_jsonb(OLD)->>'quota_limit') THEN RETURN NEW; END IF;
    subject := coalesce(snapshot->>'id',snapshot->>'team_id')::uuid;
    IF TG_TABLE_NAME='leases' THEN
        event_name := CASE WHEN TG_OP='UPDATE' AND NEW.status='ACTIVE' THEN 'RENEWED' ELSE NEW.status END;
    END IF;
    INSERT INTO audit_events(actor_id,entity_type,entity_id,event_type,details)
    VALUES(actor,TG_TABLE_NAME,subject,event_name,jsonb_strip_nulls(jsonb_build_object(
      'teamId',snapshot->>'team_id','poolId',snapshot->>'pool_id','userId',snapshot->>'user_id','reason',reason)));
    RETURN NEW;
END;
$$;
CREATE TRIGGER lease_audit AFTER INSERT OR UPDATE ON leases FOR EACH ROW EXECUTE FUNCTION record_audit_event();
CREATE TRIGGER team_audit AFTER INSERT OR UPDATE ON teams FOR EACH ROW EXECUTE FUNCTION record_audit_event();
CREATE TRIGGER membership_audit AFTER INSERT OR UPDATE ON team_memberships FOR EACH ROW EXECUTE FUNCTION record_audit_event();
CREATE TRIGGER pool_audit AFTER INSERT OR UPDATE ON resource_pools FOR EACH ROW EXECUTE FUNCTION record_audit_event();
CREATE TRIGGER quota_audit AFTER INSERT OR UPDATE ON team_quotas FOR EACH ROW EXECUTE FUNCTION record_audit_event();

