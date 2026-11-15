# tools/topis_raw.txt (서울시 TOPIS 링크, GRS80 TM) → app/src/main/assets/seoul_links.bin
# 사용: python3 make_topis_links.py
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
import sys, os
here=os.path.dirname(os.path.abspath(__file__))
raw=os.path.join(here,'topis_raw.txt'); dst=os.path.join(here,'..','app','src','main','assets','seoul_links.bin')
# 좌표계 자동 판별: 서울시 GRS80 TM은 원점 북쪽 가산값이 500000(EPSG:5181) 또는 600000(EPSG:5186)
first=next(l for l in open(raw,encoding='utf-8-sig') if ',' in l)
x0,y0=map(float,first.split(',',1)[1].split(';')[0].split())
for fn in (600000,500000):
    FN=fn
    if 37.3<inv(x0,y0)[0]<37.8: break
print('FN',FN,'첫 점',inv(x0,y0))
out=gzip.open(dst,'wt'); n=0; bad=0
for line in open(raw,encoding='utf-8-sig'):
    line=line.strip()
    if ',' not in line: continue
    lid,rest=line.split(',',1)
    try: pts=[inv(*map(float,p.split())) for p in rest.split(';') if p.strip()]
    except Exception: bad+=1; continue
    if len(pts)<2 or not (37.3<pts[0][0]<37.8): bad+=1; continue
    out.write(lid+','+';'.join('%.5f %.5f'%p for p in pts)+'\n'); n+=1
out.close(); print('saved',n,'bad',bad,'->',dst)
