

def call(){
    def tree = { [:].withDefault{ owner.call() } }
    println 'Hello from test script'




}
