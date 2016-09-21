from sqlalchemy import *
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

class UserRepository:
    def __init__(self, dbUrl):
        db = create_engine(dbUrl, echo = False)
        self.__session = sessionmaker() 
        self.__session.configure(bind=db)

        Base = declarative_base()
        class  User(Base):
            __tablename__ = "user"
            id =Column(Integer, primary_key=True)
            username = Column(String)
            address = Column(String)

        Base.metadata.create_all(db)
        self.__User = User
        
    def put(self,username, address):
        user = self.__User(username=username, address=address)
        session = self.__session()
        session.add(user)
        session.commit()

    def get(self):
        session = self.__session()
        return session.query(self.__User)

    def contains(self, address):
        return self.get().filter(self.__User.address ==  address).first() is not None
        
if __name__ == "__main__": 

    s =  UserRepository("sqlite:///signup.test.db")
    s.put("test","test@test.com")
    for i in s.get(): print(i.username,i.address)
    print(s.contains("test2"))
    #print(s.contains("test2"))
