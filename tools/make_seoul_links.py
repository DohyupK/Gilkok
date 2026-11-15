# 표준노드링크(MOCT_LINK.shp/dbf) → app/src/main/assets/seoul_links.bin
# 사용: zip에서 MOCT_LINK.* 를 풀고 같은 폴더에서 python3 make_seoul_links.py
import struct, math, gzip, collections
a=6378137.0; f=1/298.257222101; e2=f*(2-f); ep2=e2/(1-e2); k0=1.0; lat0=math.radians(38); lon0=math.radians(127); FE=200000; FN=600000
def M(p): return a*((1-e2/4-3*e2**2/64-5*e2**3/256)*p-(3*e2/8+3*e2**2/32+45*e2**3/1024)*math.sin(2*p)+(15*e2**2/256+45*e2**3/1024)*math.sin(4*p)-(35*e2**3/3072)*math.sin(6*p))
M0=M(lat0); e1=(1-math.sqrt(1-e2))/(1+math.sqrt(1-e2))
def inv(x,y):
    m=M0+(y-FN)/k0; mu=m/(a*(1-e2/4-3*e2**2/64-5*e2**3/256))
    p1=mu+(3*e1/2-27*e1**3/32)*math.sin(2*mu)+(21*e1**2/16-55*e1**4/32)*math.sin(4*mu)+(151*e1**3/96)*math.sin(6*mu)+(1097*e1**4/512)*math.sin(8*mu)
    s,c,t=math.sin(p1),math.cos(p1),math.tan(p1); C1=ep2*c*c; T1=t*t; N1=a/math.sqrt(1-e2*s*s); R1=a*(1-e2)/(1-e2*s*s)**1.5; D=(x-FE)/(N1*k0)
    lat=p1-(N1*t/R1)*(D*D/2-(5+3*T1+10*C1-4*C1*C1-9*ep2)*D**4/24+(61+90*T1+298*C1+45*T1*T1-252*ep2-3*C1*C1)*D**6/720)
    lon=lon0+(D-(1+2*T1+C1)*D**3/6+(5-2*C1+28*T1-3*C1*C1+8*ep2+24*T1*T1)*D**5/120)/c
    return math.degrees(lat),math.degrees(lon)
dbf=open('MOCT_LINK.dbf','rb'); h=dbf.read(32); n,hl,rl=struct.unpack('<IHH',h[4:12])
shp=open('MOCT_LINK.shp','rb'); shx=open('MOCT_LINK.shx','rb'); shx.seek(100)
out=gzip.open('seoul_links.bin','wt'); cnt=collections.Counter(); kept=0
for i in range(n):
    off,_=struct.unpack('>ii',shx.read(8))
    dbf.seek(hl+i*rl); r=dbf.read(rl); lid=r[1:11].decode().strip(); rank=r[41:44].decode().strip()
    if not ('100'<=lid[:3]<='124'): continue
    if rank not in ('101','102','103','104','105','106'): continue
    shp.seek(off*2+8); st,xmin,ymin,xmax,ymax,nparts,npts=struct.unpack('<i4dii',shp.read(44))
    if st not in (3,13): continue
    shp.read(4*nparts); xy=struct.unpack('<%dd'%(2*npts),shp.read(16*npts))
    la,lo=inv((xmin+xmax)/2,(ymin+ymax)/2)
    if not (37.41<la<37.72 and 126.76<lo<127.20): continue
    pts=[inv(xy[2*k],xy[2*k+1]) for k in range(npts)]
    # 간략화: 3m 이내 연속점 제거
    keep=[pts[0]]
    for p in pts[1:-1]:
        if abs(p[0]-keep[-1][0])*111000+abs(p[1]-keep[-1][1])*88000>15: keep.append(p)
    keep.append(pts[-1])
    out.write(lid+','+';'.join('%.5f %.5f'%p for p in keep)+'\n'); kept+=1; cnt[lid[:3]]+=1
out.close(); print(kept, cnt.most_common(8))
