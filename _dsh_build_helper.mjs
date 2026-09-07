import { spawn } from 'child_process';
export const JDK="C:\\Users\\30354\\.jdks\\jdk-21.0.12.1+1";
export const ENGINE="C:\\Users\\30354\\Desktop\\Shuisoi DEV\\MC\\mmtr\\engine";
export async function gradle(tasks=[], tests=[], opts={}) {
  const env={...process.env, JAVA_HOME:JDK, PATH:JDK+'\\bin;'+(process.env.PATH||'')};
  const args=['-cp',ENGINE+'\\gradle\\wrapper\\gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain','--console=plain',...tasks];
  for(const t of tests) args.push('--tests',t);
  const child=spawn(JDK+'\\bin\\java.exe',args,{cwd:ENGINE,env,stdio:['ignore','pipe','pipe']});
  let out='',err=''; child.stdout.on('data',d=>out+=d); child.stderr.on('data',d=>err+=d);
  const code=await new Promise(res=>{child.on('close',c=>res(c)); child.on('error',e=>res('ERR:'+e.message));});
  return {code,out,err};
}