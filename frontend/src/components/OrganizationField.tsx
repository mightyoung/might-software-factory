import { useEffect, useRef, useState, type Ref } from "react";
import { FindingSubmitError, type FindingSubmitAdapter, type ReportableOrganization, type ReportableOrganizationPage } from "../adapters/findingSubmit";
import { ReportField, describedByIds } from "./ReportField";

export type OrganizationsState = {status:"loading"}|{status:"error"}|({status:"ready"}&ReportableOrganizationPage);
export interface ReportableOrganizations {
  state:OrganizationsState;organizationId:string;touched:boolean;selected:ReportableOrganization|null;
  query:string; paged:boolean;pick(id:string):void;setQuery(value:string):void;nextPage():void;reload():void;reset():void;noneAvailable:boolean;
}
/** The published paging seam never auto-selects a singleton page or trusts an unvalidated restored id. */
export function useReportableOrganizations(adapter:FindingSubmitAdapter,onUnauthenticated?:()=>void,initialId="",enabled=true):ReportableOrganizations {
  const [state,setState]=useState<OrganizationsState>({status:"loading"});
  const restoreCandidate=useRef(initialId);
  const [organizationId,setOrganizationId]=useState("");const [selected,setSelected]=useState<ReportableOrganization|null>(null);
  const [touched,setTouched]=useState(initialId!=="");const [query,setQuery]=useState("");const [cursor,setCursor]=useState<string|undefined>();const [tick,setTick]=useState(0);const generation=useRef(0);
  useEffect(()=>{
    if(!enabled||(!adapter.queryOrganizations&&!adapter.listOrganizations))return;
    const abort=new AbortController();const request=++generation.current;setState({status:"loading"});
    const result=adapter.queryOrganizations?adapter.queryOrganizations({query,...(cursor?{afterId:cursor}:{}),limit:20},abort.signal):adapter.listOrganizations!(abort.signal).then(items=>({items}));
    result.then(page=>{
      if(abort.signal.aborted||request!==generation.current)return;setState({status:"ready",...page});
      const candidate=restoreCandidate.current;restoreCandidate.current="";
      setOrganizationId(current=>{
        const wanted=current||candidate;
        const found=page.items.find(item=>item.id===wanted);
        if(found){setSelected(found);return wanted;}
        if(adapter.queryOrganizations&&selected?.id===current)return current;
        if(!adapter.queryOrganizations&&page.items.length===1){setSelected(page.items[0]!);return page.items[0]!.id;}
        setSelected(null);return "";
      });
    }).catch((error:unknown)=>{
      if(abort.signal.aborted||request!==generation.current)return;setOrganizationId("");setSelected(null);setState({status:"error"});
      if(error instanceof FindingSubmitError&&error.kind==="unauthenticated")onUnauthenticated?.();
    });
    return ()=>{generation.current++;abort.abort();};
    // Selection is pinned through subsequent authorized search pages, not a reload trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[adapter,enabled,query,cursor,tick]);
  return {state,organizationId,touched,selected,query,paged:adapter.queryOrganizations!==undefined,
    pick(id){setOrganizationId(id);setSelected(id?(state.status==="ready"?state.items.find(item=>item.id===id)??selected:selected):null);setTouched(true);},
    setQuery(value){if([...value].length<=100){setQuery(value);setCursor(undefined);}},nextPage(){if(state.status==="ready")setCursor(state.nextAfterId);},reload(){setTick(value=>value+1);},
    reset(){restoreCandidate.current="";setOrganizationId("");setSelected(null);setTouched(false);setQuery("");setCursor(undefined);setTick(value=>value+1);},
    noneAvailable:state.status==="ready"&&state.items.length===0&&!selected};
}
export function OrganizationField({id,orgs,error,disabled,selectRef,label="所属组织",required=true}:{id:string;orgs:ReportableOrganizations;error?:string;disabled:boolean;selectRef:Ref<HTMLSelectElement>;label?:string;required?:boolean}) {
  const {state}=orgs;const pinned=orgs.selected&&(state.status!=="ready"||!state.items.some(item=>item.id===orgs.selected!.id))?orgs.selected:null;
  const hint=orgs.noneAvailable?(orgs.paged?"当前检索没有可上报的组织。":"你当前没有可上报的组织，无法提交。请联系管理员授权。"):undefined;
  return <ReportField id={id} label={label} required={required} error={error} hint={hint}>
    {orgs.paged&&<label><span>检索组织</span><input type="search" value={orgs.query} autoFocus disabled={disabled} onChange={event=>orgs.setQuery(event.target.value)}/></label>}
    {state.status==="error"?<p>组织列表加载失败。<button type="button" className="button button-subtle" onClick={orgs.reload}>重试</button></p>:<>
      <select id={id} ref={selectRef} value={orgs.organizationId} aria-invalid={Boolean(error)} aria-describedby={describedByIds(id,{hint:Boolean(hint),error:Boolean(error)})} disabled={disabled||state.status!=="ready"||orgs.noneAvailable} onChange={event=>orgs.pick(event.target.value)}>
        <option value="">{state.status==="loading"?"加载中…":"请选择"}</option>{pinned&&<option value={pinned.id}>{pinned.name}（已选）</option>}
        {state.status==="ready"&&state.items.map(org=><option key={org.id} value={org.id}>{orgs.paged?`${org.name}（${org.code}）`:org.name}</option>)}
      </select>
      {orgs.paged&&state.status==="ready"&&state.nextAfterId&&<button type="button" className="button button-subtle" disabled={disabled} onClick={orgs.nextPage}>下一页组织</button>}
    </>}
  </ReportField>;
}
